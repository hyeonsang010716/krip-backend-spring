package site.krip.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;
import site.krip.domain.notification.document.InboxItem;
import site.krip.domain.notification.document.TargetType;
import site.krip.domain.notification.dto.response.InboxListResponse;
import site.krip.domain.notification.repository.InboxRepository;
import site.krip.domain.notification.service.InboxService;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인박스 E2E — 경로 {@code /api/notification/inbox}. Mongo 단독. 항목은 {@link InboxRepository}
 * static 팩토리(feedLike 등)로 직접 시드. 응답 JSON snake_case.
 */
@DisplayName("인박스 — 목록·자동 read 멱등·hide·cascade·커서")
class InboxE2eTest extends IntegrationTestSupport {

    @Autowired
    private InboxRepository inboxRepo;

    @Autowired
    private InboxService inboxService;

    /** recipient 인박스에 actor 의 feed_like 항목을 직접 seed (dedup 회피용 distinct postId → upsert 는 항상 insert). */
    private InboxItem seedFeedLike(String recipientId, String actorId, String postId) {
        InboxItem item = InboxItem.feedLike(
                recipientId, actorId, "행위자", null, postId, "게시글 미리보기");
        inboxRepo.upsert(item);
        // upsert 는 전달 객체에 생성 _id 를 역기입하지 않으므로, DB 에서 distinct postId 로 재조회해 id 확보.
        return inboxRepo.findByRecipient(recipientId, null, null, 100).stream()
                .filter(i -> postId.equals(i.getTargetId()))
                .findFirst()
                .orElseThrow();
    }

    /** 목록 응답에 해당 inbox_item_id 항목이 존재하는지 단언. */
    private static ResultMatcher itemPresent(String id) {
        return jsonPath("$.items[?(@.inbox_item_id == '" + id + "')]").exists();
    }

    /** 목록 응답에서 해당 inbox_item_id 항목이 빠졌는지 단언. */
    private static ResultMatcher itemAbsent(String id) {
        return jsonPath("$.items[?(@.inbox_item_id == '" + id + "')]").doesNotExist();
    }

    /** 해당 항목의 is_read 플래그 단언. */
    private static ResultMatcher itemRead(String id, boolean read) {
        return jsonPath("$.items[?(@.inbox_item_id == '" + id + "')].is_read").value(read);
    }

    // ──────────────────── 목록 / 카운트 ────────────────────

    @Test
    @DisplayName("목록 조회 → 200, items 배열 + next_cursor")
    void listItems() throws Exception {
        String recipient = fixtures.createActiveUser("수신자");
        String actor = fixtures.createActiveUser("행위자");
        InboxItem item = seedFeedLike(recipient, actor, "post-list-1");

        mockMvc.perform(get("/api/notification/inbox")
                        .with(auth(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(itemPresent(item.getId()))
                .andExpect(jsonPath("$.items[0].type").value("feed_like"))
                .andExpect(jsonPath("$.items[0].actor_id").value(actor));
    }

    @Test
    @DisplayName("미읽음 카운트 → 200, unread_count 반영")
    void unreadCount() throws Exception {
        String recipient = fixtures.createActiveUser("카운트수신자");
        String actor = fixtures.createActiveUser("카운트행위자");
        seedFeedLike(recipient, actor, "post-count-1");
        seedFeedLike(recipient, actor, "post-count-2");

        mockMvc.perform(get("/api/notification/inbox/unread-count")
                        .with(auth(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(2));
    }

    @Test
    @DisplayName("빈 인박스 미읽음 카운트 → 200, 0")
    void unreadCountEmpty() throws Exception {
        String recipient = fixtures.createActiveUser();

        mockMvc.perform(get("/api/notification/inbox/unread-count")
                        .with(auth(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(0));
    }

    // ──────────────────── 자동 read ────────────────────

    @Test
    @DisplayName("첫 페이지 진입 시 응답은 read 전 상태(is_read=false), 재조회 시 read 반영")
    void firstPageAutoReadReflectsPreReadState() throws Exception {
        String recipient = fixtures.createActiveUser("자동읽음수신자");
        String actor = fixtures.createActiveUser("자동읽음행위자");
        InboxItem item = seedFeedLike(recipient, actor, "post-autoread-1");

        // 첫 페이지(cursor 없음) → 자동 read 처리되지만 응답 is_read 는 read 전(false).
        mockMvc.perform(get("/api/notification/inbox")
                        .with(auth(recipient)))
                .andExpect(status().isOk())
                .andExpect(itemRead(item.getId(), false));

        // 이후 미읽음 카운트는 0 (자동 read 반영).
        mockMvc.perform(get("/api/notification/inbox/unread-count")
                        .with(auth(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(0));

        // 재조회 시 is_read=true.
        mockMvc.perform(get("/api/notification/inbox")
                        .with(auth(recipient)))
                .andExpect(status().isOk())
                .andExpect(itemRead(item.getId(), true));
    }

    @Test
    @DisplayName("자동 read 는 반환한 페이지 항목만 — 안 본 다음 페이지는 미읽음 유지(unread = 본 것과 일치)")
    void autoReadMarksOnlyReturnedPage() {
        String recipient = fixtures.createActiveUser("페이지읽음수신자");
        String actor = fixtures.createActiveUser("페이지읽음행위자");
        int overflow = 5; // 한 페이지를 넘기는 잔여 건수
        int total = InboxRepository.PAGE_SIZE + overflow;
        for (int i = 0; i < total; i++) {
            seedFeedLike(recipient, actor, "post-page-" + i);
        }

        // 1페이지: 반환한 PAGE_SIZE 건만 read → unread 는 남은 5건(0 아님).
        InboxListResponse page1 = inboxService.listItems(recipient, null, true);
        assertThat(page1.items()).hasSize(InboxRepository.PAGE_SIZE);
        assertThat(inboxService.countUnread(recipient)).isEqualTo(total - InboxRepository.PAGE_SIZE);

        // 2페이지: 나머지를 보면 그때 read → unread 0.
        inboxService.listItems(recipient, page1.nextCursor(), true);
        assertThat(inboxService.countUnread(recipient)).isZero();
    }

    @Test
    @DisplayName("재조회(이미 읽음)는 추가 쓰기 없음 — markReadByIds 멱등")
    void reReadIsIdempotent() {
        String recipient = fixtures.createActiveUser("멱등수신자");
        String actor = fixtures.createActiveUser("멱등행위자");
        seedFeedLike(recipient, actor, "post-idem-1");

        inboxService.listItems(recipient, null, true);
        assertThat(inboxService.countUnread(recipient)).isZero();
        // 같은 페이지 재조회 — 이미 read 라 카운트 변화 없음.
        inboxService.listItems(recipient, null, true);
        assertThat(inboxService.countUnread(recipient)).isZero();
    }

    // ──────────────────── self-skip ────────────────────

    @Test
    @DisplayName("본인→본인 fan-out 은 skip — 인박스에 쌓이지 않음")
    void selfActionSkipped() throws Exception {
        String user = fixtures.createActiveUser("본인");

        // 서비스 fan-out 직접 호출(컨트롤러 진입점 없음) — recipient == actor → skip.
        inboxService.notifyFeedLike(user, user, "본인", null, "post-self-1", "내 글");

        mockMvc.perform(get("/api/notification/inbox/unread-count")
                        .with(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(0));
    }

    // ──────────────────── hide ────────────────────

    @Test
    @DisplayName("본인 항목 hide → 200, 이후 목록에서 제외")
    void hideOwnItem() throws Exception {
        String recipient = fixtures.createActiveUser("숨김수신자");
        String actor = fixtures.createActiveUser("숨김행위자");
        InboxItem item = seedFeedLike(recipient, actor, "post-hide-1");

        mockMvc.perform(patch("/api/notification/inbox/{itemId}/hide", item.getId())
                        .with(auth(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(get("/api/notification/inbox")
                        .with(auth(recipient)))
                .andExpect(status().isOk())
                .andExpect(itemAbsent(item.getId()));
    }

    @Test
    @DisplayName("잘못된 ObjectId 형식 hide → 404")
    void hideInvalidObjectId() throws Exception {
        String recipient = fixtures.createActiveUser();

        mockMvc.perform(patch("/api/notification/inbox/not-an-objectid/hide")
                        .with(auth(recipient)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는(유효 형식) ObjectId hide → 404")
    void hideNotFound() throws Exception {
        String recipient = fixtures.createActiveUser();

        mockMvc.perform(patch("/api/notification/inbox/0123456789abcdef01234567/hide")
                        .with(auth(recipient)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("타인 항목 hide → 404 (소유자 아님)")
    void hideNonOwner() throws Exception {
        String recipient = fixtures.createActiveUser("실수신자");
        String actor = fixtures.createActiveUser("실행위자");
        String other = fixtures.createActiveUser("타인");
        InboxItem item = seedFeedLike(recipient, actor, "post-nonowner-1");

        mockMvc.perform(patch("/api/notification/inbox/{itemId}/hide", item.getId())
                        .with(auth(other)))
                .andExpect(status().isNotFound());
    }

    // ──────────────────── cascade ────────────────────

    @Test
    @DisplayName("게시글 삭제 cascade → 해당 target 항목 목록에서 제외(soft hide)")
    void cascadePostDeletedHidesItems() throws Exception {
        String recipient = fixtures.createActiveUser("cascade수신자");
        String actor = fixtures.createActiveUser("cascade행위자");
        String postId = "post-cascade-del";
        InboxItem item = seedFeedLike(recipient, actor, postId);

        inboxService.cascadePostDeleted(TargetType.FEED_POST.getValue(), postId);

        mockMvc.perform(get("/api/notification/inbox")
                        .with(auth(recipient)))
                .andExpect(status().isOk())
                .andExpect(itemAbsent(item.getId()));
    }

    @Test
    @DisplayName("유저 탈퇴 cascade → 수신자 인박스 항목 hard delete")
    void cascadeUserWithdrawnDeletesItems() throws Exception {
        String recipient = fixtures.createActiveUser("탈퇴수신자");
        String actor = fixtures.createActiveUser("탈퇴행위자");
        seedFeedLike(recipient, actor, "post-cascade-withdraw");

        // 탈퇴 전: 항목 존재(미읽음 1).
        mockMvc.perform(get("/api/notification/inbox/unread-count")
                        .with(auth(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(1));

        inboxService.cascadeUserWithdrawn(recipient);

        // 탈퇴 후: 수신자 매칭 항목이 모두 삭제 → 빈 목록.
        mockMvc.perform(get("/api/notification/inbox")
                        .with(auth(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    // ──────────────────── 커서 ────────────────────

    @Test
    @DisplayName("유효 ISO 커서 → 200")
    void validIsoCursor() throws Exception {
        String recipient = fixtures.createActiveUser();

        mockMvc.perform(get("/api/notification/inbox")
                        .with(auth(recipient))
                        .param("cursor", "2026-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName("쓰레기 커서 → 400")
    void garbageCursorBadRequest() throws Exception {
        String recipient = fixtures.createActiveUser();

        mockMvc.perform(get("/api/notification/inbox")
                        .with(auth(recipient))
                        .param("cursor", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없이 목록 → 401")
    void listUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/notification/inbox")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
