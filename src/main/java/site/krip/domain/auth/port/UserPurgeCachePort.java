package site.krip.domain.auth.port;

/**
 * chat 도메인의 유저 세션/데이터 정리 훅 (chat 도메인 소유).
 * 로그아웃 시 해당 토큰 세션 종료, 탈퇴 요청 시 전체 세션 revoke, 영구 삭제 시 데이터성 키 정리.
 */
public interface UserPurgeCachePort {

    /** 로그아웃 — 폐기된 토큰(jti)으로 연결된 활성 chat 세션만 즉시 종료(다른 기기는 유지). */
    void revokeSessionsForToken(String userId, String tokenJti);

    /** request_withdraw post-commit — 활성 chat 세션 즉시 종료. */
    void revokeAllSessions(String userId);

    /** purge — unread:{uid} 등 TTL 없는 데이터성 키 정리. */
    void cleanupUserData(String userId);
}
