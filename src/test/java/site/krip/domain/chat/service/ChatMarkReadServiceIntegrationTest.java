package site.krip.domain.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import site.krip.domain.chat.dto.response.MessageSentAck;
import site.krip.domain.chat.entity.MessageType;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.global.common.exception.ApiException;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 읽음 처리 통합 테스트 — {@link RoomService#markRead} 의 GREATEST 회귀 방지(작은 seq 로 내려가지 않음),
 * 비멤버 거부(403), 읽음 후 unread 캐시 동기화(최신까지 읽음→0, 부분 읽음→잔여)를 실 DB 로 검증한다.
 */
@DisplayName("읽음 처리 — GREATEST 단조·미래 seq 클램프·권한")
class ChatMarkReadServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RoomService roomService;

    @Autowired
    private ChatRoomMemberRepository memberRepo;

    @Autowired
    private MessageService messageService;

    @Autowired
    private MessageHistoryService historyService;

    @Test
    @DisplayName("markRead 는 GREATEST 라 더 작은 seq 로는 내려가지 않는다")
    void markReadGreatestPreventsRegression() {
        // given
        String a = fixtures.createActiveUser("readA");
        String b = fixtures.createActiveUser("readB");
        String room = roomService.createDirectRoom(a, b).chatRoomId();

        // server_seq 를 쌓아 클램프 상한 확보 (b 송신, a 가 읽음 주체)
        MessageSentAck m1 = messageService.sendMessage(b, "sess-b", room, "c1", MessageType.TEXT, "1");
        messageService.sendMessage(b, "sess-b", room, "c2", MessageType.TEXT, "2");
        messageService.sendMessage(b, "sess-b", room, "c3", MessageType.TEXT, "3");
        MessageSentAck m4 = messageService.sendMessage(b, "sess-b", room, "c4", MessageType.TEXT, "4");
        MessageSentAck m5 = messageService.sendMessage(b, "sess-b", room, "c5", MessageType.TEXT, "5");

        long f1 = roomService.markRead(a, "sess-a", room, m4.serverSeq());
        assertThat(f1).isEqualTo(m4.serverSeq());
        assertThat(memberRepo.findLastReadSeq(room, a)).contains(m4.serverSeq());

        // 더 작은 값 → GREATEST 로 유지
        long f2 = roomService.markRead(a, "sess-a", room, m1.serverSeq());
        assertThat(f2).isEqualTo(m4.serverSeq());
        assertThat(memberRepo.findLastReadSeq(room, a)).contains(m4.serverSeq());

        // 더 큰 값(현재 seq 이내) → 갱신
        long f3 = roomService.markRead(a, "sess-a", room, m5.serverSeq());
        assertThat(f3).isEqualTo(m5.serverSeq());
        assertThat(memberRepo.findLastReadSeq(room, a)).contains(m5.serverSeq());
    }

    @Test
    @DisplayName("현재 seq 를 넘는 up_to 는 현재 seq 로 클램프된다 — 미래 seq 주입 차단")
    void markReadClampsBeyondCurrentSeq() {
        // given
        String a = fixtures.createActiveUser("clampA");
        String b = fixtures.createActiveUser("clampB");
        String room = roomService.createDirectRoom(a, b).chatRoomId();

        messageService.sendMessage(b, "sess-b", room, "c1", MessageType.TEXT, "1");
        MessageSentAck m2 = messageService.sendMessage(b, "sess-b", room, "c2", MessageType.TEXT, "2");

        // when
        long f = roomService.markRead(a, "sess-a", room, 9_999_999_999L);

        // then
        assertThat(f).isEqualTo(m2.serverSeq());
        assertThat(memberRepo.findLastReadSeq(room, a)).contains(m2.serverSeq());
    }

    @Test
    @DisplayName("최신까지 읽으면 unread 0, 부분만 읽으면 잔여 개수가 남는다")
    void markReadSyncsUnreadCache() {
        // given
        String a = fixtures.createActiveUser("uA");
        String b = fixtures.createActiveUser("uB");
        String room = roomService.createDirectRoom(a, b).chatRoomId();

        MessageSentAck m1 = messageService.sendMessage(a, "sess-a", room, "c1", MessageType.TEXT, "1");
        messageService.sendMessage(a, "sess-a", room, "c2", MessageType.TEXT, "2");
        MessageSentAck m3 = messageService.sendMessage(a, "sess-a", room, "c3", MessageType.TEXT, "3");
        assertThat(historyService.unreadCounts(b).getOrDefault(room, 0)).isEqualTo(3);

        // 부분 읽음(seq=m1) → 잔여 2
        roomService.markRead(b, "sess-b", room, m1.serverSeq());
        assertThat(historyService.unreadCounts(b).getOrDefault(room, 0)).isEqualTo(2);

        // 최신까지 읽음(seq=m3) → 0
        roomService.markRead(b, "sess-b", room, m3.serverSeq());
        assertThat(historyService.unreadCounts(b).getOrDefault(room, 0)).isZero();

        // 새 메시지 → 다시 1 (읽음 후 캐시된 0 이 무효화돼 재계산)
        messageService.sendMessage(a, "sess-a", room, "c4", MessageType.TEXT, "4");
        assertThat(historyService.unreadCounts(b).getOrDefault(room, 0)).isEqualTo(1);
    }

    @Test
    @DisplayName("비멤버가 markRead 하면 403")
    void markReadByNonMemberForbidden() {
        // given
        String a = fixtures.createActiveUser("rdA");
        String b = fixtures.createActiveUser("rdB");
        String stranger = fixtures.createActiveUser("rdStranger");
        String room = roomService.createDirectRoom(a, b).chatRoomId();

        // when & then
        assertThatThrownBy(() -> roomService.markRead(stranger, "sess-x", room, 1))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(403);
    }
}
