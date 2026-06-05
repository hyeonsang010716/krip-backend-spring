package site.krip.domain.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import site.krip.domain.auth.port.UserPurgeCachePort;
import site.krip.global.chat.ChatRedisKeys;

/**
 * 회원 탈퇴 시 chat Redis 정리 — auth {@link UserPurgeCachePort} 의 실제 구현.
 *
 * <p>탈퇴 요청 commit 후 {@code revokeAllSessions}(활성 WS 즉시 종료), 영구 삭제 시 {@code cleanupUserData}
 * (unread:{uid} 정리). 모두 best-effort(fail-open) — 호출측 핫패스를 막지 않는다.
 */
@Service
public class UserPurgeCacheService implements UserPurgeCachePort {

    private static final Logger log = LoggerFactory.getLogger(UserPurgeCacheService.class);

    private final SessionService sessionService;
    private final StringRedisTemplate redis;

    public UserPurgeCacheService(SessionService sessionService, StringRedisTemplate redis) {
        this.sessionService = sessionService;
        this.redis = redis;
    }

    @Override
    public void revokeAllSessions(String userId) {
        try {
            int count = sessionService.revokeAllSessions(userId);
            if (count > 0) {
                log.info("탈퇴 요청 — chat 세션 {}개 즉시 revoke (user_id={})", count, userId);
            }
        } catch (Exception e) {
            log.warn("탈퇴 요청 — chat 세션 revoke 실패 (TTL 만료로 fallback): user_id={}, err={}",
                    userId, e.toString());
        }
    }

    @Override
    public void cleanupUserData(String userId) {
        try {
            redis.delete(ChatRedisKeys.unread(userId));
            log.info("탈퇴 영구 삭제 — chat Redis 정리 완료 (user_id={})", userId);
        } catch (Exception e) {
            log.warn("탈퇴 영구 삭제 — chat Redis 정리 실패 (best-effort): user_id={}, err={}",
                    userId, e.toString());
        }
    }
}
