package site.krip.domain.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import site.krip.domain.feed.entity.FeedVisibility;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피드 좋아요 E2E — 추가(201)→중복(400)→목록→취소(200)→재취소(400), 가시성 미충족(404).
 * 경로: {@code /api/feed/posts/{postId}/like[s]}. 게시물은 리포지토리로 직접 시드(S3 우회).
 */
class FeedLikeE2eTest extends FeedTestSupport {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("좋아요 추가(201)→중복(400)→목록→취소(200)→재취소(400)")
    void likeLifecycle() throws Exception {
        String owner = fixtures.createActiveUser("작성자");
        String liker = fixtures.createActiveUser("좋아요유저");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        // 추가 (201)
        mockMvc.perform(post("/api/feed/posts/" + postId + "/like")
                        .with(auth(liker)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.post_id").value(postId))
                .andExpect(jsonPath("$.like_count").value(1));

        // 중복 추가 (400)
        mockMvc.perform(post("/api/feed/posts/" + postId + "/like")
                        .with(auth(liker)))
                .andExpect(status().isBadRequest());

        // 좋아요 목록 (200) — liker 포함
        mockMvc.perform(get("/api/feed/posts/" + postId + "/likes")
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(postId))
                .andExpect(jsonPath("$.users[?(@.user_id == '" + liker + "')]").exists());

        // 취소 (200)
        mockMvc.perform(delete("/api/feed/posts/" + postId + "/like")
                        .with(auth(liker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.like_count").value(0));

        // 재취소 (400)
        mockMvc.perform(delete("/api/feed/posts/" + postId + "/like")
                        .with(auth(liker)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("본인 글 본인 좋아요 허용 (201)")
    void selfLikeAllowed() throws Exception {
        String owner = fixtures.createActiveUser("자기좋아요");
        String postId = seedPost(owner, FeedVisibility.PRIVATE, null);

        mockMvc.perform(post("/api/feed/posts/" + postId + "/like")
                        .with(auth(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.like_count").value(1));
    }

    @Test
    @DisplayName("가시성 미충족 글 좋아요 → 404")
    void likeNotVisible() throws Exception {
        String owner = fixtures.createActiveUser("주인");
        String stranger = fixtures.createActiveUser("낯선이");
        String postId = seedPost(owner, FeedVisibility.FRIENDS, null);

        mockMvc.perform(post("/api/feed/posts/" + postId + "/like")
                        .with(auth(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 게시물 좋아요 → 404")
    void likeMissingPost() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(post("/api/feed/posts/no-such-post/like")
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("좋아요 목록 커서 페이지네이션 — 31명: 첫 페이지 30명+커서, 다음 페이지 1명+null 커서")
    void likedUsersPaginates() throws Exception {
        String owner = fixtures.createActiveUser("페이지작성자");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        // 31명 좋아요 — tight loop 라 created_at 이 겹쳐 (created_at, user_id) tiebreak 까지 검증된다.
        for (int i = 0; i < 31; i++) {
            String liker = fixtures.createActiveUser("좋아요" + i);
            mockMvc.perform(post("/api/feed/posts/" + postId + "/like")
                            .with(auth(liker)))
                    .andExpect(status().isCreated());
        }

        // 첫 페이지 — 30명 + next_cursor 존재
        String body = mockMvc.perform(get("/api/feed/posts/" + postId + "/likes")
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(30))
                .andExpect(jsonPath("$.next_cursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = objectMapper.readTree(body).get("next_cursor").asText();

        // 다음 페이지 — 남은 1명 + next_cursor null(마지막 페이지)
        mockMvc.perform(get("/api/feed/posts/" + postId + "/likes")
                        .param("cursor", cursor)
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.next_cursor").isEmpty());
    }
}
