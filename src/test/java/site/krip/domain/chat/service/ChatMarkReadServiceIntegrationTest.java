package site.krip.domain.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.global.common.exception.ApiException;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 읽음 처리 통합 테스트 — {@link RoomService#markRead} 의 GREATEST 회귀 방지(작은 seq 로 내려가지 않음)와
 * 비멤버 거부(403)를 실 DB 로 검증한다.
 */
class ChatMarkReadServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RoomService roomService;

    @Autowired
    private ChatRoomMemberRepository memberRepo;

    @Test
    @DisplayName("markRead 는 GREATEST 라 더 작은 seq 로는 내려가지 않는다")
    void markReadGreatestPreventsRegression() {
        String a = fixtures.createActiveUser("readA");
        String b = fixtures.createActiveUser("readB");
        String room = roomService.createDirectRoom(a, b).chatRoomId();

        long f1 = roomService.markRead(a, "sess-a", room, 5);
        assertThat(f1).isEqualTo(5);
        assertThat(memberRepo.findLastReadSeq(room, a)).contains(5L);

        // 더 작은 값(3) → GREATEST 로 5 유지
        long f2 = roomService.markRead(a, "sess-a", room, 3);
        assertThat(f2).isEqualTo(5);
        assertThat(memberRepo.findLastReadSeq(room, a)).contains(5L);

        // 더 큰 값(10) → 갱신
        long f3 = roomService.markRead(a, "sess-a", room, 10);
        assertThat(f3).isEqualTo(10);
        assertThat(memberRepo.findLastReadSeq(room, a)).contains(10L);
    }

    @Test
    @DisplayName("비멤버가 markRead 하면 403")
    void markReadByNonMemberForbidden() {
        String a = fixtures.createActiveUser("rdA");
        String b = fixtures.createActiveUser("rdB");
        String stranger = fixtures.createActiveUser("rdStranger");
        String room = roomService.createDirectRoom(a, b).chatRoomId();

        assertThatThrownBy(() -> roomService.markRead(stranger, "sess-x", room, 1))
                .isInstanceOf(ApiException.class);
    }
}
