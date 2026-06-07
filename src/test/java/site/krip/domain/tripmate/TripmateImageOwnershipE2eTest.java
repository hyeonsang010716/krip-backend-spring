package site.krip.domain.tripmate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate 이미지 소유권 검증(IDOR 방지) E2E.
 *
 * <p>게시글 생성/수정·임시저장에 첨부하는 {@code image_urls} 는 본인이 업로드한 이미지여야 한다.
 * 타인 URL 주입을 막아, "타인 URL 을 넣었다 빼서 교차 삭제"하는 공격 체인의 출발점을 차단한다.
 */
@Import(FakeStorageConfig.class)
class TripmateImageOwnershipE2eTest extends IntegrationTestSupport {

    private static final String IMAGES = "/api/tripmate/images";
    private static final String POSTS = "/api/tripmate/posts";

    @Autowired
    private ObjectMapper objectMapper;

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
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("images").get(0).get("image_url").asText();
    }

    private String createBody(String imageUrlsJson) {
        return """
                {
                  "title": "동행 모집",
                  "content": "여행 같이 떠나실 분을 찾습니다.",
                  "preferred_age_min": 20,
                  "preferred_age_max": 35,
                  "preferred_gender": "any",
                  "region": "부산",
                  "travel_start_date": "2026-09-01",
                  "travel_end_date": "2026-09-07",
                  "companion_type": "friend",
                  "image_urls": %s
                }
                """.formatted(imageUrlsJson);
    }

    private String draftBody(String imageUrlsJson) {
        return """
                {
                  "title": "임시 제목",
                  "image_urls": %s
                }
                """.formatted(imageUrlsJson);
    }

    @Test
    @DisplayName("게시글 생성: 타인 이미지 URL 첨부 → 403, 피해자 이미지는 스토리지/Mongo 에 보존")
    void createWithOthersImageRejected() throws Exception {
        String victim = fixtures.createActiveUser("피해자");
        String attacker = fixtures.createActiveUser("공격자");
        String victimUrl = uploadImage(victim, "victim.jpg");

        mockMvc.perform(post(POSTS)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("[\"" + victimUrl + "\"]")))
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
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("[]")))
                .andExpect(status().isCreated())
                .andReturn();
        String postId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("post_id").asText();

        // 수정 시 피해자 URL 주입 시도 → 403
        mockMvc.perform(put(POSTS + "/" + postId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("[\"" + victimUrl + "\"]")))
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
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody("[\"" + victimUrl + "\"]")))
                .andExpect(status().isForbidden());

        assertThat(storage.stored).contains(victimUrl);
    }

    @Test
    @DisplayName("정상 경로: 본인이 업로드한 이미지로 생성/임시저장 → 성공 (회귀 방지)")
    void ownImageAccepted() throws Exception {
        String owner = fixtures.createActiveUser("정상유저");
        String ownUrl = uploadImage(owner, "own.jpg");

        mockMvc.perform(post(POSTS)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("[\"" + ownUrl + "\"]")))
                .andExpect(status().isCreated());

        mockMvc.perform(put(POSTS + "/draft")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody("[\"" + ownUrl + "\"]")))
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
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("[\"" + ownUrl + "\",\"" + victimUrl + "\"]")))
                .andExpect(status().isForbidden());

        assertThat(imageRepository.findOwnedUrls(victim, Set.of(victimUrl))).contains(victimUrl);
    }
}
