package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.domain.feed.entity.FeedVisibility;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가시성 엣지: PENDING(미수락) friendship 은 비친구로 취급되어 FRIENDS 게시물에 접근 불가(404).
 */
class FeedPendingFriendshipVisibilityE2eTest extends FeedTestSupport {

    @Test
    @DisplayName("PENDING 친구요청 상태의 유저는 FRIENDS 게시물을 볼 수 없다(404)")
    void pendingFriendCannotSeeFriendsPost() throws Exception {
        String owner = fixtures.createActiveUser("가시성주인");
        String viewer = fixtures.createActiveUser("대기중친구");

        // owner → viewer 친구요청만 보내고 수락하지 않음 → PENDING
        sendFriendRequest(owner, viewer);

        String friendsPost = seedPost(owner, FeedVisibility.FRIENDS, null);

        mockMvc.perform(get("/api/feed/posts/{friendsPost}/likes", friendsPost)
                        .with(auth(viewer)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ACCEPTED 친구는 FRIENDS 게시물을 볼 수 있다(200, 대조군)")
    void acceptedFriendCanSeeFriendsPost() throws Exception {
        String owner = fixtures.createActiveUser("가시성주인2");
        String friend = fixtures.createActiveUser("수락친구");
        makeFriends(owner, friend);

        String friendsPost = seedPost(owner, FeedVisibility.FRIENDS, null);

        mockMvc.perform(get("/api/feed/posts/{friendsPost}/likes", friendsPost)
                        .with(auth(friend)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post_id").value(friendsPost));
    }
}
