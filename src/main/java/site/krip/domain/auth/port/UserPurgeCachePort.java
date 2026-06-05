package site.krip.domain.auth.port;

/**
 * chat 도메인의 유저 세션/데이터 정리 훅 (chat 도메인 소유).
 * 탈퇴 요청 시 활성 WS 세션 즉시 revoke, 영구 삭제 시 데이터성 키 정리.
 */
public interface UserPurgeCachePort {

    /** request_withdraw post-commit — 활성 chat 세션 즉시 종료. */
    void revokeAllSessions(String userId);

    /** purge — unread:{uid} 등 TTL 없는 데이터성 키 정리. */
    void cleanupUserData(String userId);
}
