package site.krip.domain.tripmate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate 좋아요 E2E — 추가(201)→중복추가(400)→좋아요 유저 목록→취소(200)→재취소(400).
 * 본인→본인 좋아요는 서비스에서 허용(fan-out만 skip)되므로 self-like 가 성공함을 검증.
 */
class TripmateLikeE2eTest extends TripmateTestSupport {

    @Test
    @DisplayName("좋아요 추가(201)→중복추가(400)→유저목록→취소(200)→재취소(400)")
    void likeLifecycle() throws Exception {
        String author = fixtures.createActiveUser("글쓴이");
        String liker = fixtures.createActiveUser("좋아요누른이");
        String postId = createPost(author);

        // 추가 (201)
        mockMvc.perform(post("/api/tripmate/posts/{postId}/like", postId)
                        .with(auth(liker)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.post_id").value(postId))
                .andExpect(jsonPath("$.like_count").value(1));

        // 중복 추가 (400)
        mockMvc.perform(post("/api/tripmate/posts/{postId}/like", postId)
                        .with(auth(liker)))
                .andExpect(status().isBadRequest());

        // 좋아요 누른 유저 목록 — liker 포함
        mockMvc.perform(get("/api/tripmate/posts/{postId}/likes", postId)
                        .with(auth(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(postId))
                .andExpect(jsonPath("$.user_ids[?(@ == '" + liker + "')]").exists());

        // 취소 (200)
        mockMvc.perform(delete("/api/tripmate/posts/{postId}/like", postId)
                        .with(auth(liker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.like_count").value(0));

        // 재취소 (400)
        mockMvc.perform(delete("/api/tripmate/posts/{postId}/like", postId)
                        .with(auth(liker)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("본인 게시글 self-like 는 허용된다(201)")
    void selfLikeAllowed() throws Exception {
        String author = fixtures.createActiveUser("셀프좋아요");
        String postId = createPost(author);

        mockMvc.perform(post("/api/tripmate/posts/{postId}/like", postId)
                        .with(auth(author)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.like_count").value(1));
    }

    @Test
    @DisplayName("좋아요 후 단건 조회 시 is_liked=true, like_count 반영")
    void likeReflectedInDetail() throws Exception {
        String author = fixtures.createActiveUser("작성자L");
        String liker = fixtures.createActiveUser("좋아요L");
        String postId = createPost(author);

        mockMvc.perform(post("/api/tripmate/posts/{postId}/like", postId)
                        .with(auth(liker)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/tripmate/posts/{postId}", postId)
                        .with(auth(liker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.like_count").value(1))
                .andExpect(jsonPath("$.is_liked").value(true));
    }

    @Test
    @DisplayName("존재하지 않는 게시글 좋아요 추가 → 404 (단건 조회와 동일한 존재 은닉)")
    void likeMissingPost() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(post("/api/tripmate/posts/no-such-post/like")
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 게시글 좋아요 유저 목록 → 404")
    void likedUsersMissingPost() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(get("/api/tripmate/posts/no-such-post/likes")
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }
}
