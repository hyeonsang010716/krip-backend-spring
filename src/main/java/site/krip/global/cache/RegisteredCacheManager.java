package site.krip.global.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import site.krip.global.config.AuthProperties;

import java.time.Duration;

/**
 * {@code REGISTERED:{user_id}} 플래그 캐시.
 *
 * <p>"ACTIVE & 2차 회원가입 완료" 양성 결과만 캐싱한다. 탈퇴 요청 시 무효화되며,
 * 무효화 호출은 반드시 트랜잭션 commit **이후**에 한다(미커밋 ACTIVE 행 재캐싱 race 방지).
 */
@Component
public class RegisteredCacheManager {

    private static final Logger log = LoggerFactory.getLogger(RegisteredCacheManager.class);
    private static final String PREFIX = "REGISTERED";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RegisteredCacheManager(StringRedisTemplate redis, AuthProperties props) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(props.registeredCacheTtlSeconds());
    }

    private String key(String userId) {
        return PREFIX + ":" + userId;
    }

    public boolean exists(String userId) {
        return Boolean.TRUE.equals(redis.hasKey(key(userId)));
    }

    /** 양성 플래그 세팅 (기본 TTL 24h). */
    public void setFlag(String userId) {
        redis.opsForValue().set(key(userId), "1", ttl);
    }

    /** 무효화 — 실패해도 TTL 만료로 자연 정리되므로 로그만 남기고 swallow. */
    public void invalidate(String userId) {
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            log.warn("REGISTERED 캐시 무효화 실패 (user_id={}): {}", userId, e.toString());
        }
    }
}
