package site.krip.domain.notification.port;

/**
 * 전역(유저) 알림 차단 적용 — User 를 소유한 auth 도메인이 구현.
 */
public interface GlobalMutePort {

    void setGlobalMute(String userId, boolean muted);
}
