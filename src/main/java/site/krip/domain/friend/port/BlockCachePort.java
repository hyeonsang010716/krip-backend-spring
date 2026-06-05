package site.krip.domain.friend.port;

/**
 * chat 도메인의 차단 캐시 무효화 훅 (chat 도메인 소유). 차단/해제 시 stale 캐시 제거.
 * chat 도메인 구현 전까지 stub.
 */
public interface BlockCachePort {

    void invalidateBlockCache(String blockerId, String blockedId);
}
