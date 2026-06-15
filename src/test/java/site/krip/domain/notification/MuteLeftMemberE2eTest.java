package site.krip.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.chat.entity.ChatRoom;
import site.krip.domain.chat.entity.ChatRoomMember;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.domain.chat.repository.ChatRoomRepository;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 방별 mute — 떠난(left) 멤버 경계 E2E ({@code /api/notification/mute/rooms/{id}}).
 *
 * <p>{@link MuteE2eTest} 가 활성 멤버(정상)·비멤버(400)를 다룬다. 본 테스트는 "멤버 행은 있으나
 * 이미 퇴장(isLeft=true)" 인 별도 분기를 메운다 — 활성 멤버가 아니므로 400 이어야 한다.
 */
class MuteLeftMemberE2eTest extends IntegrationTestSupport {

    @Autowired
    private ChatRoomRepository roomRepo;

    @Autowired
    private ChatRoomMemberRepository memberRepo;

    @Autowired
    private TransactionTemplate txTemplate;

    @Test
    @DisplayName("이미 퇴장한 멤버가 방 mute 시도 → 400 (활성 멤버 아님)")
    void leftMemberRoomMuteRejected() throws Exception {
        String userId = fixtures.createActiveUser("퇴장한멤버");
        String peerId = fixtures.createActiveUser("남은멤버");

        ChatRoom room = roomRepo.saveAndFlush(ChatRoom.group(userId, "퇴장테스트 방"));
        String roomId = room.getChatRoomId();
        memberRepo.saveAndFlush(new ChatRoomMember(roomId, userId, 0L));
        // @Modifying 쿼리라 tx 필요 — 생산(RoomService.leave/kick)과 동일하게 txTemplate 로 감싼다.
        txTemplate.execute(s -> memberRepo.markLeftIfActive(roomId, userId));
        memberRepo.saveAndFlush(new ChatRoomMember(roomId, peerId, 0L));

        mockMvc.perform(put("/api/notification/mute/rooms/" + roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"muted\": true}"))
                .andExpect(status().isBadRequest());
    }
}
