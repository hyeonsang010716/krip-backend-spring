package site.krip.domain.chat.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.chat.entity.ChatRoomMember;
import site.krip.domain.chat.entity.ChatRoomMemberId;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.domain.notification.port.RoomMutePort;
import site.krip.global.common.exception.ApiException;

/**
 * notification 의 {@link RoomMutePort} 구현 — chat 도메인이 멤버 방별 알림 차단을 적용. 활성 멤버만 허용.
 */
@Component
@RequiredArgsConstructor
public class RoomMuteAdapter implements RoomMutePort {

    private final ChatRoomMemberRepository memberRepo;

    @Override
    @Transactional
    public void setRoomMute(String userId, String chatRoomId, boolean muted) {
        ChatRoomMember member = memberRepo.findById(new ChatRoomMemberId(chatRoomId, userId)).orElse(null);
        if (member == null || member.isLeft()) {
            throw ApiException.badRequest("이 방의 활성 멤버가 아닙니다.");
        }
        member.applyNotificationMute(muted);
    }
}
