package site.krip.domain.tripmate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import site.krip.support.FakeObjectStorage;
import site.krip.support.FakeStorageConfig;
import site.krip.support.IntegrationTestSupport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
    private ObjectMapper objectMapper;

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
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
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
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("허용되지 않는 content-type → 400")
    void wrongContentTypeRejected() throws Exception {
        String userId = fixtures.createActiveUser("img타입");
        mockMvc.perform(multipart(IMAGES)
                        .file(new MockMultipartFile("files", "x.pdf", "application/pdf", new byte[]{1}))
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("content-type 은 이미지지만 실제 이미지 바이트가 아니면 → 400 (재인코딩 파이프라인 거절)")
    void nonImageBytesRejected() throws Exception {
        String userId = fixtures.createActiveUser("img위장");
        mockMvc.perform(multipart(IMAGES)
                        .file(new MockMultipartFile("files", "fake.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("gif 는 허용 목록에서 제외 → 400")
    void gifRejected() throws Exception {
        String userId = fixtures.createActiveUser("imggif");
        mockMvc.perform(multipart(IMAGES)
                        .file(new MockMultipartFile("files", "a.gif", "image/gif", new byte[]{1}))
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("10MB 초과 파일 → 400 (컨트롤러 검증)")
    void oversizeRejected() throws Exception {
        String userId = fixtures.createActiveUser("img크기");
        byte[] big = new byte[10 * 1024 * 1024 + 1024];
        mockMvc.perform(multipart(IMAGES)
                        .file(new MockMultipartFile("files", "big.jpg", "image/jpeg", big))
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("고아 정리: 어디에도 참조되지 않은 업로드 이미지를 삭제(deleted_count), 재실행은 0")
    void cleanupRemovesOrphans() throws Exception {
        String userId = fixtures.createActiveUser("img정리");

        MvcResult res = mockMvc.perform(multipart(IMAGES).file(jpeg("a.jpg")).file(jpeg("b.jpg"))
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isCreated())
                .andReturn();
        List<String> urls = new ArrayList<>();
        objectMapper.readTree(res.getResponse().getContentAsString()).get("images")
                .forEach(img -> urls.add(img.get("image_url").asText()));

        mockMvc.perform(post(IMAGES + "/cleanup")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_count").value(2));

        assertThat(storage.stored).doesNotContainAnyElementsOf(urls);

        // 재실행 → 더 이상 고아 없음
        mockMvc.perform(post(IMAGES + "/cleanup")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_count").value(0));
    }
}
