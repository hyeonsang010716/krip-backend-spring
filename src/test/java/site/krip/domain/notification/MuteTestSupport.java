package site.krip.domain.notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import site.krip.domain.chat.entity.ChatRoom;
import site.krip.domain.chat.entity.ChatRoomMember;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.domain.chat.repository.ChatRoomRepository;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** mute E2E 공용 — 방+멤버 시드와 mute PUT 호출 헬퍼를 모은 베이스. */
abstract class MuteTestSupport extends IntegrationTestSupport {

    @Autowired
    protected ChatRoomRepository roomRepo;

    @Autowired
    protected ChatRoomMemberRepository memberRepo;

    /** 그룹 채팅방 + 두 활성 멤버를 RDB 에 직접 시드하고 room_id 반환. */
    protected String seedRoomWithMember(String userId, String peerId) {
        ChatRoom room = roomRepo.saveAndFlush(ChatRoom.group(userId, "테스트 방"));
        String roomId = room.getChatRoomId();
        memberRepo.saveAndFlush(new ChatRoomMember(roomId, userId, 0L));
        memberRepo.saveAndFlush(new ChatRoomMember(roomId, peerId, 0L));
        return roomId;
    }

    /** 전역 mute PUT. */
    protected ResultActions putGlobalMute(String userId, boolean muted) throws Exception {
        return mockMvc.perform(put("/api/notification/mute/global")
                .with(auth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("muted", muted)));
    }

    /** 방별 mute PUT. */
    protected ResultActions putRoomMute(String userId, String roomId, boolean muted) throws Exception {
        return mockMvc.perform(put("/api/notification/mute/rooms/{roomId}", roomId)
                .with(auth(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("muted", muted)));
    }
}
