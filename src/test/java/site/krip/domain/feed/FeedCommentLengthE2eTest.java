package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 댓글 길이 검증이 코드포인트 기준인지 확인하는 E2E — 컨트롤러 단계라 게시물 없이도 검증(한도 내면 404=통과 증거).
 * 이모지(비-BMP)는 UTF-16 2 유닛이라 과거 코드유닛 기준에선 과도 거부되던 경계를 본다.
 */
@DisplayName("피드 댓글 길이 — 코드포인트 한도")
class FeedCommentLengthE2eTest extends IntegrationTestSupport {

    private static final String COMMENTS = "/api/feed/posts/nope/comments";
    private static final int MAX_CODEPOINTS = 500;

    private void postComment(String userId, String content, org.springframework.test.web.servlet.ResultMatcher expect)
            throws Exception {
        mockMvc.perform(post(COMMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("content", content))
                        .with(auth(userId)))
                .andExpect(expect);
    }

    // ASCII/이모지 모두 코드포인트 기준 초과 → 같은 검증 경로로 400.
    private static Stream<String> overLimitContents() {
        return Stream.of("a".repeat(MAX_CODEPOINTS + 1), "😀".repeat(MAX_CODEPOINTS + 1));
    }

    @ParameterizedTest
    @MethodSource("overLimitContents")
    @DisplayName("코드포인트 501 초과(ASCII/이모지) → 400")
    void overLimitRejected(String content) throws Exception {
        postComment(fixtures.createActiveUser(), content, status().isBadRequest());
    }

    @Test
    @DisplayName("이모지 500개(=500 코드포인트, 1000 UTF-16) → 한도 내 통과, 미존재 게시물이라 404")
    void emojiWithinLimitPasses() throws Exception {
        postComment(fixtures.createActiveUser(), "😀".repeat(MAX_CODEPOINTS), status().isNotFound());
    }
}
