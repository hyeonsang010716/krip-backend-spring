package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.domain.feed.entity.FeedVisibility;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피드 댓글 E2E — 작성(201)→공백(400)→목록→작성자만 삭제(타인 403)→post_id 불일치(404).
 * 경로: {@code /api/feed/posts/{postId}/comments}. 게시물은 리포지토리로 직접 시드(S3 우회).
 */
class FeedCommentE2eTest extends FeedTestSupport {

    @Test
    @DisplayName("댓글 작성(201)→목록→작성자 삭제(200)")
    void commentLifecycle() throws Exception {
        String owner = fixtures.createActiveUser("작성자");
        String commenter = fixtures.createActiveUser("댓글러");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        // 작성 (201)
        String commentId = comment(commenter, postId, "좋은 사진이네요");

        mockMvc.perform(get("/api/feed/posts/{postId}/comments", postId)
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments[?(@.comment_id == '" + commentId + "')]").exists())
                .andExpect(jsonPath("$.comments[?(@.content == '좋은 사진이네요')]").exists());

        // 작성자 본인 삭제 (200)
        mockMvc.perform(delete("/api/feed/posts/{postId}/comments/{commentId}", postId, commentId)
                        .with(auth(commenter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("공백만 댓글 → 400")
    void blankComment() throws Exception {
        String owner = fixtures.createActiveUser("작성자2");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        // "   " 는 @Size(min=1) 통과 후 서비스 strip 에서 400.
        mockMvc.perform(post("/api/feed/posts/{postId}/comments", postId)
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("content", "   ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("빈 문자열 댓글 → 400 (@Size min=1)")
    void emptyComment() throws Exception {
        String owner = fixtures.createActiveUser("작성자3");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(post("/api/feed/posts/{postId}/comments", postId)
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("content", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("작성자 아닌 유저(게시물 owner 포함)가 삭제 → 403")
    void onlyAuthorCanDelete() throws Exception {
        String owner = fixtures.createActiveUser("게시물주인");
        String commenter = fixtures.createActiveUser("댓글작성자");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);
        String commentId = comment(commenter, postId, "내 댓글입니다");

        // 게시물 owner 라도 댓글 작성자가 아니면 삭제 불가 → 403
        mockMvc.perform(delete("/api/feed/posts/{postId}/comments/{commentId}", postId, commentId)
                        .with(auth(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("post_id 불일치로 댓글 삭제 → 404")
    void deleteCommentPostIdMismatch() throws Exception {
        String owner = fixtures.createActiveUser("주인");
        String commenter = fixtures.createActiveUser("댓글러2");
        String postA = seedPost(owner, FeedVisibility.PUBLIC, null);
        String postB = seedPost(owner, FeedVisibility.PUBLIC, null);
        String commentId = comment(commenter, postA, "A 게시물 댓글");

        // postB 경로로 postA 의 댓글 삭제 시도 → 댓글-게시물 불일치 404
        mockMvc.perform(delete("/api/feed/posts/{postB}/comments/{commentId}", postB, commentId)
                        .with(auth(commenter)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 댓글 삭제 → 404")
    void deleteMissingComment() throws Exception {
        String owner = fixtures.createActiveUser("주인2");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(delete("/api/feed/posts/{postId}/comments/no-such-comment", postId)
                        .with(auth(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("가시성 미충족 글에 댓글 작성 → 404")
    void commentNotVisible() throws Exception {
        String owner = fixtures.createActiveUser("주인3");
        String stranger = fixtures.createActiveUser("낯선이");
        String postId = seedPost(owner, FeedVisibility.PRIVATE, null);

        mockMvc.perform(post("/api/feed/posts/{postId}/comments", postId)
                        .with(auth(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("content", "숨겨진 글 댓글")))
                .andExpect(status().isNotFound());
    }
}
