package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.domain.feed.entity.FeedVisibility;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피드 팝업 E2E — 프로필 + 가시성 부분집합 피드. 경로: {@code GET /api/feed/popup/{userId}}.
 * user 미존재·차단 404, 가시성은 viewer 관계에 따라 PUBLIC/FRIENDS+PUBLIC/전체.
 */
class FeedPopupE2eTest extends FeedTestSupport {

    @Test
    @DisplayName("비친구 팝업: 프로필 + PUBLIC 만 노출")
    void strangerPopupPublicOnly() throws Exception {
        String owner = fixtures.createActiveUser("팝업주인");
        String stranger = fixtures.createActiveUser("낯선이");
        String pub = seedPost(owner, FeedVisibility.PUBLIC, null);
        String fr = seedPost(owner, FeedVisibility.FRIENDS, null);
        String priv = seedPost(owner, FeedVisibility.PRIVATE, null);

        mockMvc.perform(get("/api/feed/popup/{owner}", owner)
                        .with(auth(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(owner))
                .andExpect(jsonPath("$.user_name").value("팝업주인"))
                .andExpect(jsonPath("$.feed.items[?(@.post_id == '" + pub + "')]").exists())
                .andExpect(jsonPath("$.feed.items[?(@.post_id == '" + fr + "')]").doesNotExist())
                .andExpect(jsonPath("$.feed.items[?(@.post_id == '" + priv + "')]").doesNotExist());
    }

    @Test
    @DisplayName("친구 팝업: FRIENDS+PUBLIC 노출")
    void friendPopupFriendsAndPublic() throws Exception {
        String owner = fixtures.createActiveUser("팝업주인2");
        String friend = fixtures.createActiveUser("친구");
        makeFriends(owner, friend);
        String pub = seedPost(owner, FeedVisibility.PUBLIC, null);
        String fr = seedPost(owner, FeedVisibility.FRIENDS, null);
        String priv = seedPost(owner, FeedVisibility.PRIVATE, null);

        mockMvc.perform(get("/api/feed/popup/{owner}", owner)
                        .with(auth(friend)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.items[?(@.post_id == '" + pub + "')]").exists())
                .andExpect(jsonPath("$.feed.items[?(@.post_id == '" + fr + "')]").exists())
                .andExpect(jsonPath("$.feed.items[?(@.post_id == '" + priv + "')]").doesNotExist());
    }

    @Test
    @DisplayName("차단 관계 유저 팝업 → 404 (차단 사실 은닉)")
    void blockedPopupNotFound() throws Exception {
        String owner = fixtures.createActiveUser("팝업주인3");
        String blockedViewer = fixtures.createActiveUser("차단된이");
        block(owner, blockedViewer);

        mockMvc.perform(get("/api/feed/popup/{owner}", owner)
                        .with(auth(blockedViewer)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 유저 팝업 → 404")
    void missingUserPopup() throws Exception {
        String viewer = fixtures.createActiveUser("뷰어");
        mockMvc.perform(get("/api/feed/popup/no-such-user")
                        .with(auth(viewer)))
                .andExpect(status().isNotFound());
    }
}
