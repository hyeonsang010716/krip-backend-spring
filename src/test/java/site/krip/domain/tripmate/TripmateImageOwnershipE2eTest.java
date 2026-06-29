package site.krip.domain.tripmate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.domain.tripmate.repository.TripmateImageRepository;
import site.krip.support.FakeObjectStorage;
import site.krip.support.FakeStorageConfig;
import site.krip.support.IntegrationTestSupport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate 이미지 소유권 검증(IDOR 방지) E2E — 첨부 {@code image_urls} 는 본인 업로드만 허용.
 * 타인 URL 주입을 막아 "넣었다 빼서 교차 삭제"하는 공격 체인의 출발점을 차단한다.
 */
@Import(FakeStorageConfig.class)
class TripmateImageOwnershipE2eTest extends TripmateTestSupport {

    private static final String IMAGES = "/api/tripmate/images";

    @Autowired
    private FakeObjectStorage storage;

    @Autowired
    private TripmateImageRepository imageRepository;

    private MockMultipartFile jpeg(String name) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", buf);
        return new MockMultipartFile("files", name, "image/jpeg", buf.toByteArray());
    }

    /** 이미지 1건 업로드 후 image_url 반환. */
    private String uploadImage(String userId, String name) throws Exception {
        MvcResult res = mockMvc.perform(multipart(IMAGES).file(jpeg(name))
                        .with(auth(userId)))
                .andExpect(status().isCreated())
                .andReturn();
        return readJson(res)
                .get("images").get(0).get("image_url").asText();
    }

    private String draftBody(List<String> imageUrls) {
        return json("title", "임시 제목", "image_urls", imageUrls);
    }

    /** 부산 지역 모집글을 image_urls 와 함께 생성 후 post_id 반환. */
    private String createPost(String userId, List<String> imageUrls) throws Exception {
        return createPostRaw(userId, postBody("동행 모집", "여행 같이 떠나실 분을 찾습니다.", "부산", imageUrls));
    }

    @Test
    @DisplayName("게시글 생성: 타인 이미지 URL 첨부 → 403, 피해자 이미지는 스토리지/Mongo 에 보존")
    void createWithOthersImageRejected() throws Exception {
        String victim = fixtures.createActiveUser("피해자");
        String attacker = fixtures.createActiveUser("공격자");
        String victimUrl = uploadImage(victim, "victim.jpg");

        mockMvc.perform(post(POSTS)
                        .with(auth(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("동행 모집", "여행 같이 떠나실 분을 찾습니다.", "부산", List.of(victimUrl))))
                .andExpect(status().isForbidden());

        // 주입이 막혔으므로 피해자 이미지는 그대로 살아있어야 한다.
        assertThat(storage.stored).contains(victimUrl);
        assertThat(imageRepository.findOwnedUrls(victim, List.of(victimUrl))).contains(victimUrl);
    }

    @Test
    @DisplayName("게시글 수정: 타인 이미지 URL 첨부 → 403 (교차 삭제 출발점 차단)")
    void updateWithOthersImageRejected() throws Exception {
        String victim = fixtures.createActiveUser("피해자2");
        String attacker = fixtures.createActiveUser("공격자2");
        String victimUrl = uploadImage(victim, "victim2.jpg");

        // 공격자가 자기 글을 정상 생성(이미지 없음).
        MvcResult created = mockMvc.perform(post(POSTS)
                        .with(auth(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("동행 모집", "여행 같이 떠나실 분을 찾습니다.", "부산", List.of())))
                .andExpect(status().isCreated())
                .andReturn();
        String postId = idFrom(created, "post_id");

        // 수정 시 피해자 URL 주입 시도 → 403
        mockMvc.perform(put(POSTS + "/" + postId)
                        .with(auth(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("동행 모집", "여행 같이 떠나실 분을 찾습니다.", "부산", List.of(victimUrl))))
                .andExpect(status().isForbidden());

        assertThat(storage.stored).contains(victimUrl);
        assertThat(imageRepository.findOwnedUrls(victim, List.of(victimUrl))).contains(victimUrl);
    }

    @Test
    @DisplayName("임시저장: 타인 이미지 URL 첨부 → 403")
    void saveDraftWithOthersImageRejected() throws Exception {
        String victim = fixtures.createActiveUser("피해자3");
        String attacker = fixtures.createActiveUser("공격자3");
        String victimUrl = uploadImage(victim, "victim3.jpg");

        mockMvc.perform(put(POSTS + "/draft")
                        .with(auth(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody(List.of(victimUrl))))
                .andExpect(status().isForbidden());

        assertThat(storage.stored).contains(victimUrl);
    }

    @Test
    @DisplayName("정상 경로: 본인이 업로드한 이미지로 생성/임시저장 → 성공 (회귀 방지)")
    void ownImageAccepted() throws Exception {
        String owner = fixtures.createActiveUser("정상유저");
        String ownUrl = uploadImage(owner, "own.jpg");

        mockMvc.perform(post(POSTS)
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("동행 모집", "여행 같이 떠나실 분을 찾습니다.", "부산", List.of(ownUrl))))
                .andExpect(status().isCreated());

        mockMvc.perform(put(POSTS + "/draft")
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody(List.of(ownUrl))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("부분 위조: 본인+타인 URL 혼합 첨부 → 403 (containsAll 검증)")
    void mixedOwnAndOthersRejected() throws Exception {
        String victim = fixtures.createActiveUser("피해자4");
        String attacker = fixtures.createActiveUser("공격자4");
        String victimUrl = uploadImage(victim, "victim4.jpg");
        String ownUrl = uploadImage(attacker, "own4.jpg");

        mockMvc.perform(post(POSTS)
                        .with(auth(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("동행 모집", "여행 같이 떠나실 분을 찾습니다.", "부산", List.of(ownUrl, victimUrl))))
                .andExpect(status().isForbidden());

        assertThat(imageRepository.findOwnedUrls(victim, Set.of(victimUrl))).contains(victimUrl);
    }

    @Test
    @DisplayName("게시글 삭제: 같은 이미지를 쓰는 다른 글이 있으면 공유 이미지는 보존된다")
    void deletePreservesImageSharedByAnotherPost() throws Exception {
        String owner = fixtures.createActiveUser("공유삭제");
        String sharedUrl = uploadImage(owner, "shared.jpg");
        String p1 = createPost(owner, List.of(sharedUrl));
        createPost(owner, List.of(sharedUrl)); // P2 도 같은 이미지 사용

        mockMvc.perform(delete(POSTS + "/" + p1)
                        .with(auth(owner)))
                .andExpect(status().isOk());

        // P2 가 아직 참조 → 스토리지/Mongo 보존
        assertThat(storage.stored).contains(sharedUrl);
        assertThat(imageRepository.findOwnedUrls(owner, List.of(sharedUrl))).contains(sharedUrl);
    }

    @Test
    @DisplayName("게시글 수정: 제거한 이미지가 드래프트에 남아있으면 보존된다")
    void updatePreservesImageStillInDraft() throws Exception {
        String owner = fixtures.createActiveUser("공유수정");
        String sharedUrl = uploadImage(owner, "shared2.jpg");
        String postId = createPost(owner, List.of(sharedUrl));

        mockMvc.perform(put(POSTS + "/draft") // 같은 이미지를 드래프트에도 저장
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody(List.of(sharedUrl))))
                .andExpect(status().isOk());

        mockMvc.perform(put(POSTS + "/" + postId) // 게시글에서 이미지 제거(빈 배열)
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("동행 모집", "여행 같이 떠나실 분을 찾습니다.", "부산", List.of())))
                .andExpect(status().isOk());

        // 드래프트가 아직 참조 → 보존
        assertThat(storage.stored).contains(sharedUrl);
        assertThat(imageRepository.findOwnedUrls(owner, List.of(sharedUrl))).contains(sharedUrl);
    }

    @Test
    @DisplayName("게시글 삭제: 공유 없는 이미지는 정상 정리된다 (과교정 방지)")
    void deleteCleansUpUnsharedImage() throws Exception {
        String owner = fixtures.createActiveUser("단독삭제");
        String soloUrl = uploadImage(owner, "solo.jpg");
        String postId = createPost(owner, List.of(soloUrl));

        mockMvc.perform(delete(POSTS + "/" + postId)
                        .with(auth(owner)))
                .andExpect(status().isOk());

        // 어디에도 참조 없음 → 즉시 정리
        assertThat(storage.stored).doesNotContain(soloUrl);
        assertThat(imageRepository.findOwnedUrls(owner, List.of(soloUrl))).isEmpty();
    }
}
