package site.krip.domain.notification.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.chat.entity.ChatRoomMember;
import site.krip.domain.chat.entity.ChatRoomMemberId;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.global.common.exception.ApiException;

/**
 * 알림 차단(mute) — 전역(유저) / 방별(멤버) 두 레벨.
 * True 만 저장, 해제는 NULL.
 */
@Service
public class MuteService {

    private final UserRepository userRepo;
    private final ChatRoomMemberRepository memberRepo;

    public MuteService(UserRepository userRepo, ChatRoomMemberRepository memberRepo) {
        this.userRepo = userRepo;
        this.memberRepo = memberRepo;
    }

    @Transactional
    public void setGlobalMute(String userId, boolean muted) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ApiException(400, "존재하지 않는 유저입니다."));
        user.applyNotificationMute(muted);
        userRepo.flush();
    }

    @Transactional
    public void setRoomMute(String userId, String chatRoomId, boolean muted) {
        ChatRoomMember member = memberRepo.findById(new ChatRoomMemberId(chatRoomId, userId)).orElse(null);
        if (member == null || member.isLeft()) {
            throw new ApiException(400, "이 방의 활성 멤버가 아닙니다.");
        }
        member.applyNotificationMute(muted);
        memberRepo.flush();
    }
}
