package site.krip.domain.auth.port;

/**
 * 알림 인박스 cascade (notification 도메인 소유). 탈퇴 영구 삭제 시 호출.
 */
public interface InboxCascadePort {

    void cascadeUserWithdrawn(String userId);
}
