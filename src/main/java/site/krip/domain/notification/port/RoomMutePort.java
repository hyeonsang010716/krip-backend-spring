package site.krip.domain.notification.port;

/**
 * 방별 알림 차단 적용 — ChatRoomMember 를 소유한 chat 도메인이 구현.
 */
public interface RoomMutePort {

    void setRoomMute(String userId, String chatRoomId, boolean muted);
}
