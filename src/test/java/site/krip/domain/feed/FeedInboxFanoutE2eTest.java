package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.domain.feed.entity.FeedVisibility;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * feed 좋아요/댓글 → notification 인박스 fan-out E2E (소스 도메인 관점).
 * 외부 액터 fan-out, self-skip, dedup(멱등), 댓글 preview 100자 절단을 인박스 목록으로 검증.
 */
class FeedInboxFanoutE2eTest extends FeedTestSupport {

    private void like(String liker, String postId) throws Exception {
        mockMvc.perform(post("/api/feed/posts/" + postId + "/like")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(liker)))
                .andExpect(status().isCreated());
    }

    private void unlike(String liker, String postId) throws Exception {
        mockMvc.perform(delete("/api/feed/posts/" + postId + "/like")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(liker)))
                .andExpect(status().isOk());
    }

    private void comment(String commenter, String postId, String content) throws Exception {
        mockMvc.perform(post("/api/feed/posts/" + postId + "/comments")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(commenter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("외부 유저 좋아요 → 게시자 인박스에 feed_like 1건(actor=좋아요러)")
    void externalLikeCreatesInboxItem() throws Exception {
        String owner = fixtures.createActiveUser("피드주인");
        String liker = fixtures.createActiveUser("좋아요러");
        String post = seedPost(owner, FeedVisibility.PUBLIC, "예쁜 사진");

        like(liker, post);

        mockMvc.perform(get("/api/notification/inbox")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.type=='feed_like')]", hasSize(1)))
                .andExpect(jsonPath("$.items[0].actor_id").value(liker))
                .andExpect(jsonPath("$.items[0].target_id").value(post));
    }

    @Test
    @DisplayName("본인 게시물 좋아요 → 인박스 생성 없음(self-skip)")
    void selfLikeCreatesNoInboxItem() throws Exception {
        String owner = fixtures.createActiveUser("자기좋아요");
        String post = seedPost(owner, FeedVisibility.PUBLIC, null);

        like(owner, post);

        mockMvc.perform(get("/api/notification/inbox")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    @DisplayName("좋아요→취소→재좋아요 → 인박스 feed_like 는 1건만(dedup 멱등)")
    void relikeIsIdempotent() throws Exception {
        String owner = fixtures.createActiveUser("멱등주인");
        String liker = fixtures.createActiveUser("멱등좋아요러");
        String post = seedPost(owner, FeedVisibility.PUBLIC, null);

        like(liker, post);
        unlike(liker, post);
        like(liker, post);

        mockMvc.perform(get("/api/notification/inbox")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.type=='feed_like')]", hasSize(1)));
    }

    @Test
    @DisplayName("외부 댓글 → 인박스 feed_comment(comment_id + 본문 preview)")
    void externalCommentCreatesInboxItem() throws Exception {
        String owner = fixtures.createActiveUser("댓글주인");
        String commenter = fixtures.createActiveUser("댓글러");
        String post = seedPost(owner, FeedVisibility.PUBLIC, null);

        comment(commenter, post, "좋은 사진이네요");

        mockMvc.perform(get("/api/notification/inbox")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("feed_comment"))
                .andExpect(jsonPath("$.items[0].comment_id").exists())
                .andExpect(jsonPath("$.items[0].comment_preview").value("좋은 사진이네요"));
    }

    @Test
    @DisplayName("150자 댓글 → 인박스 preview 는 100자 + '…' 로 절단")
    void longCommentPreviewTruncatedTo100() throws Exception {
        String owner = fixtures.createActiveUser("긴댓글주인");
        String commenter = fixtures.createActiveUser("긴댓글러");
        String post = seedPost(owner, FeedVisibility.PUBLIC, null);

        comment(commenter, post, "x".repeat(150));

        String expected = "x".repeat(100) + "…";
        mockMvc.perform(get("/api/notification/inbox")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("feed_comment"))
                .andExpect(jsonPath("$.items[0].comment_preview").value(expected));
    }
}
