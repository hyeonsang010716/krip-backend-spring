package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피드 업로드 검증 E2E — S3 미사용이라 S3 도달 전 거절되는 경로만 본다({@code POST /api/feed/posts}).
 * 컨트롤러는 content-type → size → caption 길이를 S3/이미지 처리보다 먼저 검증한다.
 */
class FeedUploadValidationE2eTest extends IntegrationTestSupport {

    private static MockMultipartFile file(String contentType, byte[] bytes) {
        return new MockMultipartFile("file", "image.jpg", contentType, bytes);
    }

    @Test
    @DisplayName("허용되지 않는 content-type → 400 (S3 미도달)")
    void wrongContentType() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(multipart("/api/feed/posts")
                        .file(file("application/pdf", new byte[]{1, 2, 3}))
                        .param("visibility", "public")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("text/plain content-type → 400 (S3 미도달)")
    void textContentType() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(multipart("/api/feed/posts")
                        .file(file("text/plain", "not an image".getBytes()))
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("10MB 초과 파일 → 400 (컨트롤러 size 검증, S3 미도달)")
    void oversizedFile() throws Exception {
        String userId = fixtures.createActiveUser();
        // 컨트롤러 MAX_FILE_SIZE(10MB)는 넘되 servlet max-file-size(11MB)는 안 넘게 → 컨트롤러 검증에서 400.
        byte[] big = new byte[10 * 1024 * 1024 + 1024];
        mockMvc.perform(multipart("/api/feed/posts")
                        .file(file("image/jpeg", big))
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("캡션 101자(코드포인트) → 400 (이미지 처리/S3 전에 거절 — 최근 수정 버그)")
    void overLengthCaption() throws Exception {
        String userId = fixtures.createActiveUser();
        String caption101 = "가".repeat(101); // 101 코드포인트
        mockMvc.perform(multipart("/api/feed/posts")
                        .file(file("image/jpeg", new byte[]{1, 2, 3}))
                        .param("caption", caption101)
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("디코드 불가 바이트(유효 content-type) → 400 (이미지 처리기 거절, S3 미도달)")
    void decodeFailBytes() throws Exception {
        String userId = fixtures.createActiveUser();
        // content-type/size/caption 검증은 통과하지만 실제 바이트가 이미지가 아니므로 처리기가 400.
        byte[] garbage = "this-is-not-a-real-jpeg-payload".getBytes();
        mockMvc.perform(multipart("/api/feed/posts")
                        .file(file("image/jpeg", garbage))
                        .param("visibility", "public")
                        .param("caption", "정상 캡션")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없이 업로드 → 401")
    void uploadUnauthenticated() throws Exception {
        mockMvc.perform(multipart("/api/feed/posts")
                        .file(file("image/jpeg", new byte[]{1, 2, 3})))
                .andExpect(status().isUnauthorized());
    }
}
