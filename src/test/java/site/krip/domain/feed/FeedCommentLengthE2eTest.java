package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 댓글 길이 검증이 코드포인트 기준인지 확인하는 E2E — 컨트롤러 단계라 게시물 없이도 검증(한도 내면 404=통과 증거).
 * 이모지(비-BMP)는 UTF-16 2 유닛이라 과거 코드유닛 기준에선 과도 거부되던 경계를 본다.
 */
class FeedCommentLengthE2eTest extends IntegrationTestSupport {

    private static final String COMMENTS = "/api/feed/posts/nope/comments";

    private void postComment(String userId, String content, org.springframework.test.web.servlet.ResultMatcher expect)
            throws Exception {
        mockMvc.perform(post(COMMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("content", content))
                        .with(auth(userId)))
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
