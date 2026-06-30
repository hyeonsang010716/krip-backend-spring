package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.domain.feed.entity.FeedVisibility;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인박스 cascade(게시물 삭제 → soft hide) + dedup partial-unique(hide 후 재좋아요 → 신규 항목) E2E.
 * feed 헬퍼(seedPost) 재사용을 위해 feed 패키지의 FeedTestSupport 를 상속.
 */
@DisplayName("피드 인박스 cascade — 삭제 soft hide·재좋아요 재생성")
class FeedInboxCascadeAndDedupE2eTest extends FeedTestSupport {

    private static final String INBOX = "/api/notification/inbox";

    private void assertFeedLikeCount(String owner, int expected) throws Exception {
        mockMvc.perform(get(INBOX)
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.type=='feed_like')]", hasSize(expected)));
    }

    private String firstInboxItemId(String owner) throws Exception {
        MvcResult res = mockMvc.perform(get(INBOX)
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(res).get("items").get(0).get("inbox_item_id").asText();
    }

    @Test
    @DisplayName("게시물 삭제 → 해당 게시물의 인박스 항목이 cascade 로 soft hide(목록에서 제외)")
    void feedPostDeleteCascadeHidesInbox() throws Exception {
        // given
        String owner = fixtures.createActiveUser("cascade주인");
        String liker = fixtures.createActiveUser("cascade좋아요러");
        String post = seedPost(owner, FeedVisibility.PUBLIC, null);

        like(liker, post);
        assertFeedLikeCount(owner, 1);

        // when & then
        mockMvc.perform(delete("/api/feed/posts/{post}", post)
                        .with(auth(owner)))
                .andExpect(status().isOk());

        assertFeedLikeCount(owner, 0);
    }

    @Test
    @DisplayName("항목 hide 후 재좋아요 → 새 visible 항목 생성(partial-unique 는 display=true 만)")
    void hideThenRelikeCreatesNewVisibleItem() throws Exception {
        String owner = fixtures.createActiveUser("dedup주인");
        String liker = fixtures.createActiveUser("dedup좋아요러");
        String post = seedPost(owner, FeedVisibility.PUBLIC, null);

        like(liker, post);
        assertFeedLikeCount(owner, 1);

        String itemId = firstInboxItemId(owner);
        mockMvc.perform(patch("/api/notification/inbox/{itemId}/hide", itemId)
                        .with(auth(owner)))
                .andExpect(status().isOk());
        assertFeedLikeCount(owner, 0);

        unlike(liker, post);
        like(liker, post);
        assertFeedLikeCount(owner, 1);
    }
}
