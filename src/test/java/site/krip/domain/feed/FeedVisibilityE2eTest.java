package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.domain.feed.entity.FeedVisibility;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피드 가시성 매트릭스 E2E — PRIVATE/FRIENDS/PUBLIC × (owner/friend/stranger/blocked).
 * 단건 조회는 {@code GET /api/feed/posts/{postId}} 가 아니라 owner 전용이므로, 타 유저 가시성은
 * 좋아요 진입점({@code POST .../like})으로 검증한다(가시성 미충족·차단 모두 404).
 *
 * <p>owner 단건은 {@code /api/feed/posts/{postId}} 가 본인 글만 반환(loadOwnedPost)하고,
 * 타 유저 피드 목록은 {@code /api/feed/users/{ownerId}} 가 viewer 가시성 부분집합만 반환한다.
 */
class FeedVisibilityE2eTest extends FeedTestSupport {

    // ──────────────────── owner 본인 ────────────────────

    @Test
    @DisplayName("owner 는 자기 PRIVATE/FRIENDS/PUBLIC 단건 모두 조회 (200)")
    void ownerSeesAllOwnPosts() throws Exception {
        String owner = fixtures.createActiveUser("주인");
        String priv = seedPost(owner, FeedVisibility.PRIVATE, null);
        String friends = seedPost(owner, FeedVisibility.FRIENDS, null);
        String pub = seedPost(owner, FeedVisibility.PUBLIC, null);

        for (String postId : new String[]{priv, friends, pub}) {
            mockMvc.perform(get("/api/feed/posts/" + postId)
                            .header("Authorization", bearer())
                            .header("X-Auth-Token", userToken(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.post_id").value(postId))
                    .andExpect(jsonPath("$.user_id").value(owner));
        }
    }

    @Test
    @DisplayName("owner 본인 피드 목록(/me) 에 PRIVATE 포함 (200)")
    void ownerMyFeedIncludesPrivate() throws Exception {
        String owner = fixtures.createActiveUser("주인2");
        String priv = seedPost(owner, FeedVisibility.PRIVATE, null);

        mockMvc.perform(get("/api/feed/me")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + priv + "')]").exists());
    }

    // ──────────────────── friend 시점 ────────────────────

    @Test
    @DisplayName("친구 피드 목록: FRIENDS+PUBLIC 노출, PRIVATE 숨김")
    void friendSeesFriendsAndPublic() throws Exception {
        String owner = fixtures.createActiveUser("주인3");
        String friend = fixtures.createActiveUser("친구");
        makeFriends(owner, friend);

        String priv = seedPost(owner, FeedVisibility.PRIVATE, null);
        String fr = seedPost(owner, FeedVisibility.FRIENDS, null);
        String pub = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(get("/api/feed/users/" + owner)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(friend)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + fr + "')]").exists())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + pub + "')]").exists())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + priv + "')]").doesNotExist());
    }

    // ──────────────────── stranger 시점 ────────────────────

    @Test
    @DisplayName("비친구 피드 목록: PUBLIC 만 노출, FRIENDS/PRIVATE 숨김")
    void strangerSeesOnlyPublic() throws Exception {
        String owner = fixtures.createActiveUser("주인4");
        String stranger = fixtures.createActiveUser("낯선이");

        String priv = seedPost(owner, FeedVisibility.PRIVATE, null);
        String fr = seedPost(owner, FeedVisibility.FRIENDS, null);
        String pub = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(get("/api/feed/users/" + owner)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + pub + "')]").exists())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + fr + "')]").doesNotExist())
                .andExpect(jsonPath("$.posts[?(@.post_id == '" + priv + "')]").doesNotExist());
    }

    // ──────────────────── 단건 가시성 (좋아요 진입점으로 검증) ────────────────────

    @Test
    @DisplayName("비친구가 FRIENDS 글 접근 → 404 (가시성 미충족)")
    void strangerFriendsPostNotVisible() throws Exception {
        String owner = fixtures.createActiveUser("주인5");
        String stranger = fixtures.createActiveUser("낯선이2");
        String fr = seedPost(owner, FeedVisibility.FRIENDS, null);

        mockMvc.perform(get("/api/feed/posts/" + fr + "/likes")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("비친구가 PRIVATE 글 접근 → 404")
    void strangerPrivatePostNotVisible() throws Exception {
        String owner = fixtures.createActiveUser("주인6");
        String stranger = fixtures.createActiveUser("낯선이3");
        String priv = seedPost(owner, FeedVisibility.PRIVATE, null);

        mockMvc.perform(get("/api/feed/posts/" + priv + "/likes")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("비친구가 PUBLIC 글 접근 → 200 (likes 조회 가능)")
    void strangerPublicPostVisible() throws Exception {
        String owner = fixtures.createActiveUser("주인7");
        String stranger = fixtures.createActiveUser("낯선이4");
        String pub = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(get("/api/feed/posts/" + pub + "/likes")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(pub));
    }

    @Test
    @DisplayName("차단 관계 유저의 PUBLIC 글 접근 → 404 (차단 사실 은닉)")
    void blockedUserNotFound() throws Exception {
        String owner = fixtures.createActiveUser("주인8");
        String blockedViewer = fixtures.createActiveUser("차단된이");
        block(owner, blockedViewer); // owner 가 viewer 를 차단 (helper 는 방향 무관)
        String pub = seedPost(owner, FeedVisibility.PUBLIC, null);

        mockMvc.perform(get("/api/feed/posts/" + pub + "/likes")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(blockedViewer)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 게시물 접근 → 404")
    void missingPostNotFound() throws Exception {
        String viewer = fixtures.createActiveUser("뷰어");
        mockMvc.perform(get("/api/feed/posts/no-such-post/likes")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(viewer)))
                .andExpect(status().isNotFound());
    }
}
