package site.krip.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 방별 mute — 떠난(left) 멤버 경계 E2E. 멤버 행은 있으나 이미 퇴장(isLeft=true)인 분기는
 * 활성 멤버가 아니므로 400 이어야 한다.
 */
class MuteLeftMemberE2eTest extends MuteTestSupport {

    @Autowired
    private TransactionTemplate txTemplate;

    @Test
    @DisplayName("이미 퇴장한 멤버가 방 mute 시도 → 400 (활성 멤버 아님)")
    void leftMemberRoomMuteRejected() throws Exception {
        String userId = fixtures.createActiveUser("퇴장한멤버");
        String peerId = fixtures.createActiveUser("남은멤버");
        String roomId = seedRoomWithMember(userId, peerId);

        // @Modifying 쿼리라 tx 필요 — 생산(RoomService.leave/kick)과 동일하게 txTemplate 로 감싼다.
        txTemplate.execute(s -> memberRepo.markLeftIfActive(roomId, userId));

        putRoomMute(userId, roomId, true).andExpect(status().isBadRequest());
    }
}
