package site.krip.domain.notification.service;

import org.springframework.stereotype.Service;
import site.krip.domain.notification.port.GlobalMutePort;
import site.krip.domain.notification.port.RoomMutePort;

/**
 * 알림 차단(mute) — 전역(유저) / 방별(멤버) 두 레벨.
 * 실제 엔티티 적용은 각 소유 도메인 어댑터(auth/chat)에 위임. True 만 저장, 해제는 NULL.
 */
@Service
public class MuteService {

    private final GlobalMutePort globalMutePort;
    private final RoomMutePort roomMutePort;

    public MuteService(GlobalMutePort globalMutePort, RoomMutePort roomMutePort) {
        this.globalMutePort = globalMutePort;
        this.roomMutePort = roomMutePort;
    }

    public void setGlobalMute(String userId, boolean muted) {
        globalMutePort.setGlobalMute(userId, muted);
    }

    public void setRoomMute(String userId, String chatRoomId, boolean muted) {
        roomMutePort.setRoomMute(userId, chatRoomId, muted);
    }
}
