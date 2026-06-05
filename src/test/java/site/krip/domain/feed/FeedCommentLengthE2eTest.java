package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 댓글 길이 검증이 <b>코드포인트</b> 기준인지 확인하는 E2E.
 *
 * <p>길이 검사는 서비스(게시물 조회) 이전 컨트롤러 단계에서 수행되므로 실제 게시물이 없어도 검증된다:
 * 초과면 400, 한도 내면 검증 통과 후 미존재 게시물이라 404(=길이는 통과했다는 증거).
 * 이모지(비-BMP)는 UTF-16 으로 2 유닛이라 과거 {@code @Size}(코드유닛) 기준에선 과도 거부되던 경계를 본다.
 */
class FeedCommentLengthE2eTest extends IntegrationTestSupport {

    private static final String COMMENTS = "/api/feed/posts/nope/comments";

    private void postComment(String userId, String content, org.springframework.test.web.servlet.ResultMatcher expect)
            throws Exception {
        mockMvc.perform(post(COMMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId)))
                .andExpect(expect);
    }

    @Test
    @DisplayName("ASCII 501자 → 400 (코드포인트 초과)")
    void asciiOverLimit() throws Exception {
        postComment(fixtures.createActiveUser(), "a".repeat(501), status().isBadRequest());
    }

    @Test
    @DisplayName("이모지 500개(=500 코드포인트, 1000 UTF-16) → 한도 내 통과, 미존재 게시물이라 404")
    void emojiWithinLimitPasses() throws Exception {
        postComment(fixtures.createActiveUser(), "😀".repeat(500), status().isNotFound());
    }

    @Test
    @DisplayName("이모지 501개 → 400 (코드포인트 초과)")
    void emojiOverLimit() throws Exception {
        postComment(fixtures.createActiveUser(), "😀".repeat(501), status().isBadRequest());
    }
}
