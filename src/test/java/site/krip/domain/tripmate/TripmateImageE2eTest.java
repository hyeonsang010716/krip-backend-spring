package site.krip.domain.tripmate;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import site.krip.domain.tripmate.document.TripmateImage;
import site.krip.support.FakeObjectStorage;
import site.krip.support.FakeStorageConfig;
import site.krip.support.IntegrationTestSupport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여행메이트 이미지 업로드/정리 E2E
 * — 인메모리 스토리지로 다건 업로드·검증·고아정리(참조 합집합 차집합)를 검증한다.
 */
@Import(FakeStorageConfig.class)
class TripmateImageE2eTest extends IntegrationTestSupport {

    private static final String IMAGES = "/api/tripmate/images";

    @Autowired
    private FakeObjectStorage storage;

    @Autowired
    private MongoTemplate mongo;

    /** 업로드 직후의 유예기간을 벗어나도록 해당 유저 이미지의 timestamp 를 과거로 당긴다. */
    private void ageImages(String userId) {
        mongo.updateMulti(
                Query.query(Criteria.where("user_id").is(userId)),
                Update.update("timestamp", Instant.now().minus(1, ChronoUnit.HOURS)),
                TripmateImage.class);
    }

    private MockMultipartFile jpeg(String name) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", buf);
        return new MockMultipartFile("files", name, "image/jpeg", buf.toByteArray());
    }

    @Test
    @DisplayName("다건 업로드 → 201, 응답 URL 들이 스토리지에 적재된다")
    void uploadStoresAllFiles() throws Exception {
        String userId = fixtures.createActiveUser("img업로드");

        MvcResult res = mockMvc.perform(multipart(IMAGES).file(jpeg("a.jpg")).file(jpeg("b.jpg"))
                        .with(auth(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.images.length()").value(2))
                .andReturn();

        for (JsonNode img : objectMapper.readTree(res.getResponse().getContentAsString()).get("images")) {
            assertThat(storage.stored).contains(img.get("image_url").asText());
        }
    }

    @Test
    @DisplayName("11개 초과 업로드 → 400")
    void tooManyFilesRejected() throws Exception {
        String userId = fixtures.createActiveUser("img과다");
        MockMultipartHttpServletRequestBuilder req = multipart(IMAGES);
        for (int i = 0; i < 11; i++) {
            req.file(jpeg("f" + i + ".jpg"));
        }
        mockMvc.perform(req
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("허용되지 않는 content-type → 400")
    void wrongContentTypeRejected() throws Exception {
        String userId = fixtures.createActiveUser("img타입");
        mockMvc.perform(multipart(IMAGES)
                        .file(new MockMultipartFile("files", "x.pdf", "application/pdf", new byte[]{1}))
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("content-type 은 이미지지만 실제 이미지 바이트가 아니면 → 400 (재인코딩 파이프라인 거절)")
    void nonImageBytesRejected() throws Exception {
        String userId = fixtures.createActiveUser("img위장");
        mockMvc.perform(multipart(IMAGES)
                        .file(new MockMultipartFile("files", "fake.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("gif 는 허용 목록에서 제외 → 400")
    void gifRejected() throws Exception {
        String userId = fixtures.createActiveUser("imggif");
        mockMvc.perform(multipart(IMAGES)
                        .file(new MockMultipartFile("files", "a.gif", "image/gif", new byte[]{1}))
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("10MB 초과 파일 → 400 (컨트롤러 검증)")
    void oversizeRejected() throws Exception {
        String userId = fixtures.createActiveUser("img크기");
        byte[] big = new byte[10 * 1024 * 1024 + 1024];
        mockMvc.perform(multipart(IMAGES)
                        .file(new MockMultipartFile("files", "big.jpg", "image/jpeg", big))
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("고아 정리: 유예기간 지난 미참조 이미지를 삭제(deleted_count), 재실행은 0")
    void cleanupRemovesOrphans() throws Exception {
        String userId = fixtures.createActiveUser("img정리");

        MvcResult res = mockMvc.perform(multipart(IMAGES).file(jpeg("a.jpg")).file(jpeg("b.jpg"))
                        .with(auth(userId)))
                .andExpect(status().isCreated())
                .andReturn();
        List<String> urls = new ArrayList<>();
        objectMapper.readTree(res.getResponse().getContentAsString()).get("images")
                .forEach(img -> urls.add(img.get("image_url").asText()));

        // 방금 올린(유예기간 내) 이미지는 작성 중일 수 있어 보호된다 — 즉시 정리해도 0건.
        mockMvc.perform(post(IMAGES + "/cleanup")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_count").value(0));
        assertThat(storage.stored).containsAll(urls);

        // 유예기간을 벗어나면 미참조 이미지로 정리된다.
        ageImages(userId);
        mockMvc.perform(post(IMAGES + "/cleanup")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_count").value(2));

        assertThat(storage.stored).doesNotContainAnyElementsOf(urls);

        // 재실행 → 더 이상 고아 없음
        mockMvc.perform(post(IMAGES + "/cleanup")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_count").value(0));
    }

    @Test
    @DisplayName("고아 정리: S3 삭제 부분 실패 시 해당 메타데이터는 보존돼 다음 호출에서 재시도된다")
    void cleanupPreservesMetadataOnPartialS3Failure() throws Exception {
        String userId = fixtures.createActiveUser("img부분실패");

        MvcResult res = mockMvc.perform(multipart(IMAGES).file(jpeg("a.jpg")).file(jpeg("b.jpg"))
                        .with(auth(userId)))
                .andExpect(status().isCreated())
                .andReturn();
        List<String> urls = new ArrayList<>();
        objectMapper.readTree(res.getResponse().getContentAsString()).get("images")
                .forEach(img -> urls.add(img.get("image_url").asText()));
        ageImages(userId);

        // 첫 URL 의 S3 삭제를 실패시킨다 → 그 한 건은 정리되지 않고 메타데이터가 남아야 한다.
        String failUrl = urls.get(0);
        storage.failDeletion.add(failUrl);

        mockMvc.perform(post(IMAGES + "/cleanup")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_count").value(1));

        // 실패분은 S3 객체와 메타데이터 모두 잔존 → 다음 호출에서 재시도 가능(영구 누수 아님).
        assertThat(storage.stored).contains(failUrl);
        assertThat(mongo.find(
                Query.query(Criteria.where("user_id").is(userId)), TripmateImage.class)).hasSize(1);

        // S3 가 회복되면 재실행에서 정상 정리된다.
        storage.failDeletion.clear();
        mockMvc.perform(post(IMAGES + "/cleanup")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_count").value(1));
        assertThat(storage.stored).doesNotContain(failUrl);
    }
}
