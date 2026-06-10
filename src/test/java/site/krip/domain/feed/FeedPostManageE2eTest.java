package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.domain.feed.entity.FeedVisibility;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피드 게시물 관리 E2E — 캡션 PATCH(설정/삭제/길이검증), 가시성 PATCH, 삭제→삭제후 404.
 * 캡션 길이 검증은 컨트롤러가 <b>코드포인트</b> 기준으로 수행하므로 이모지 케이스를 PATCH 로 검증한다
 * (PATCH 는 S3 미사용 — 시드한 게시물을 그대로 변경). 경로: {@code /api/feed/posts/{postId}}.
 */
class FeedPostManageE2eTest extends FeedTestSupport {

    // ──────────────────── 캡션 PATCH ────────────────────

    @Test
    @DisplayName("캡션 설정 → 200, 캡션 반영")
    void setCaption() throws Exception {
        String owner = fixtures.createActiveUser("주인");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(patch("/api/feed/posts/" + postId + "/caption")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\": \"새로운 캡션\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(postId))
                .andExpect(jsonPath("$.caption").value("새로운 캡션"));
    }

    @Test
    @DisplayName("캡션 삭제(공백만 → null) → 200, caption null")
    void clearCaption() throws Exception {
        String owner = fixtures.createActiveUser("주인2");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, "지울 캡션");

        mockMvc.perform(patch("/api/feed/posts/" + postId + "/caption")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\": \"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caption").doesNotExist());
    }

    @Test
    @DisplayName("캡션 101자(코드포인트) PATCH → 400")
    void captionTooLong() throws Exception {
        String owner = fixtures.createActiveUser("주인3");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        String caption101 = "가".repeat(101);
        mockMvc.perform(patch("/api/feed/posts/" + postId + "/caption")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\": \"" + caption101 + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이모지 100자(코드포인트 100, UTF-16 200) PATCH → 200 (코드포인트 카운팅 검증)")
    void captionEmojiCodePointCount() throws Exception {
        String owner = fixtures.createActiveUser("주인4");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        // U+1F600 은 1 코드포인트지만 String.length()는 2 → 코드포인트 카운팅이면 100 으로 통과해야 함.
        String emoji100 = "😀".repeat(100);
        mockMvc.perform(patch("/api/feed/posts/" + postId + "/caption")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\": \"" + emoji100 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caption").value(emoji100));
    }

    @Test
    @DisplayName("이모지 101자(코드포인트 101) PATCH → 400")
    void captionEmojiOverCodePoint() throws Exception {
        String owner = fixtures.createActiveUser("주인5");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        String emoji101 = "😀".repeat(101);
        mockMvc.perform(patch("/api/feed/posts/" + postId + "/caption")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\": \"" + emoji101 + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("타인이 캡션 변경 → 404 (비소유자엔 존재 은닉, FeedAccessService 정책과 동일)")
    void captionByOtherNotFound() throws Exception {
        String owner = fixtures.createActiveUser("주인6");
        String other = fixtures.createActiveUser("타인");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(patch("/api/feed/posts/" + postId + "/caption")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caption\": \"뺏으려는 캡션\"}"))
                .andExpect(status().isNotFound());
    }

    // ──────────────────── 가시성 PATCH ────────────────────

    @Test
    @DisplayName("가시성 변경 PUBLIC→PRIVATE → 200")
    void updateVisibility() throws Exception {
        String owner = fixtures.createActiveUser("주인7");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(patch("/api/feed/posts/" + postId + "/visibility")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\": \"private\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(postId))
                .andExpect(jsonPath("$.visibility").value("private"));
    }

    @Test
    @DisplayName("가시성 PATCH 후 재조회 시 변경이 영속된다 — 생성자 투영 엔티티 dirty checking 검증")
    void updateVisibilityPersists() throws Exception {
        String owner = fixtures.createActiveUser("주인7b");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(patch("/api/feed/posts/" + postId + "/visibility")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\": \"private\"}"))
                .andExpect(status().isOk());

        // 별도 요청(새 트랜잭션)으로 재조회 — DB 영속 확인
        mockMvc.perform(get("/api/feed/posts/" + postId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("private"));
    }

    @Test
    @DisplayName("잘못된 visibility 값 → 400")
    void updateVisibilityBadValue() throws Exception {
        String owner = fixtures.createActiveUser("주인8");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(patch("/api/feed/posts/" + postId + "/visibility")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\": \"bogus\"}"))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────── 삭제 ────────────────────

    @Test
    @DisplayName("게시물 삭제(200) → 삭제 후 단건 404")
    void deleteThenNotFound() throws Exception {
        String owner = fixtures.createActiveUser("주인9");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(delete("/api/feed/posts/" + postId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(get("/api/feed/posts/" + postId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("타인이 게시물 삭제 → 404 (비소유자엔 존재 은닉, FeedAccessService 정책과 동일)")
    void deleteByOther() throws Exception {
        String owner = fixtures.createActiveUser("주인10");
        String other = fixtures.createActiveUser("타인2");
        String postId = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(delete("/api/feed/posts/" + postId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(other)))
                .andExpect(status().isNotFound());
    }
}
