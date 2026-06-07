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

    /** 캐시 조회 — Redis 장애 시 fail-open(미스로 간주)하여 DB 검증으로 폴백한다(인증 요청 전수 500 방지). */
    public boolean exists(String userId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(userId)));
        } catch (Exception e) {
            // 인증 핫패스라 장애 시 요청마다 호출됨 — 로그 폭주를 피해 debug. Redis 장애 자체는 인프라/타 컴포넌트로 관측.
            log.debug("REGISTERED 캐시 조회 실패, DB 폴백 (user_id={}): {}", userId, e.toString());
            return false;
        }
    }

    /** 양성 플래그 세팅 (기본 TTL 24h) — best-effort. 실패해도 이미 DB 검증을 통과했으므로 요청은 진행. */
    public void setFlag(String userId) {
        try {
            redis.opsForValue().set(key(userId), "1", ttl);
        } catch (Exception e) {
            log.debug("REGISTERED 캐시 기록 실패 (user_id={}): {}", userId, e.toString());
        }
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
