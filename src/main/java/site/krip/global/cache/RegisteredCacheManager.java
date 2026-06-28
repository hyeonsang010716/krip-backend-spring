package site.krip.global.cache;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import site.krip.global.config.AuthProperties;

import java.time.Duration;

/**
 * {@code REGISTERED:{user_id}} 판정 캐시.
 *
 * <p>회원가입/상태 검증 결과(REGISTERED / UNREGISTERED / INACTIVE / SUSPENDED)를 캐싱해 인증 핫패스의 DB 조회를 생략한다.
 * 양성뿐 아니라 음성 결과도 캐싱해 미가입(403)·탈퇴유예(419) 유저가 요청마다 DB 를 때리지 않게 한다.
 * 상태 전이(가입완료/탈퇴/취소/삭제) 시 무효화되며, 음성 결과는 무효화 누락 대비 짧은 TTL 로 자연 회복한다.
 */
@Component
public class RegisteredCacheManager {

    private static final Logger log = LoggerFactory.getLogger(RegisteredCacheManager.class);
    private static final String PREFIX = "REGISTERED";
    /** 음성 결과 TTL — 상태 전이 시 무효화하지만, 무효화 실패 대비 짧게 잡아 자연 회복시킨다. */
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(60);

    /** 캐시된 회원가입/상태 판정 결과. */
    public enum Outcome {
        REGISTERED,   // ACTIVE + 2차 가입 완료 → 통과
        UNREGISTERED, // 2차 가입 미완료 → 403
        INACTIVE,     // 탈퇴 유예 → 419
        SUSPENDED     // 정지 → 403
    }

    private final StringRedisTemplate redis;
    private final Duration positiveTtl;

    public RegisteredCacheManager(StringRedisTemplate redis, AuthProperties props) {
        this.redis = redis;
        this.positiveTtl = Duration.ofSeconds(props.registeredCacheTtlSeconds());
    }

    private String key(String userId) {
        return PREFIX + ":" + userId;
    }

    /** 캐시된 판정 결과. 미스/Redis 장애 시 null(fail-open) → 호출측이 DB 로 폴백한다. */
    public @Nullable Outcome lookup(String userId) {
        try {
            String value = redis.opsForValue().get(key(userId));
            return value == null ? null : decode(value);
        } catch (Exception e) {
            // 인증 핫패스라 장애 시 요청마다 호출됨 — 로그 폭주를 피해 debug. Redis 장애는 인프라로 관측.
            log.debug("REGISTERED 캐시 조회 실패, DB 폴백 (user_id={}): {}", userId, e.toString());
            return null;
        }
    }

    /** 판정 결과 캐싱(best-effort) — REGISTERED 는 기본 TTL, 음성 결과는 짧은 TTL. 실패해도 요청은 진행. */
    public void cache(String userId, Outcome outcome) {
        Duration ttl = outcome == Outcome.REGISTERED ? positiveTtl : NEGATIVE_TTL;
        try {
            redis.opsForValue().set(key(userId), encode(outcome), ttl);
        } catch (Exception e) {
            log.debug("REGISTERED 캐시 기록 실패 (user_id={}): {}", userId, e.toString());
        }
    }

    /** 양성(REGISTERED) 여부 단축 조회 — WS 핸드셰이크용. */
    public boolean exists(String userId) {
        return lookup(userId) == Outcome.REGISTERED;
    }

    /** 양성(REGISTERED) 플래그 기록 — WS 핸드셰이크·2차 가입 완료용. */
    public void setFlag(String userId) {
        cache(userId, Outcome.REGISTERED);
    }

    /** 무효화 — 상태 전이 시 호출. 실패해도 TTL 만료로 자연 정리되므로 로그만 남기고 swallow. */
    public void invalidate(String userId) {
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            log.warn("REGISTERED 캐시 무효화 실패 (user_id={})", userId, e);
        }
    }

    private static String encode(Outcome outcome) {
        return switch (outcome) {
            case REGISTERED -> "R";
            case UNREGISTERED -> "U";
            case INACTIVE -> "I";
            case SUSPENDED -> "S";
        };
    }

    private static @Nullable Outcome decode(String value) {
        return switch (value) {
            case "R" -> Outcome.REGISTERED;
            case "U" -> Outcome.UNREGISTERED;
            case "I" -> Outcome.INACTIVE;
            case "S" -> Outcome.SUSPENDED;
            default -> null; // 구버전/미상 값 → 미스로 간주해 DB 재검증
        };
    }
}
