package site.krip.global.auth.jwt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 로그인 JWT 단건 폐기(로그아웃) — jti 를 Redis 에 토큰 만료 시각까지 보관.
 *
 * <p>가용성 우선 fail-open: Redis 장애 시 폐기 등록은 건너뛰고, 검증은 미폐기로 간주한다
 * (짧은 장애 동안 폐기 토큰이 통과할 수 있으나 인증 전체가 막히지 않게).
 */
@Component
@Slf4j
public class TokenRevocationService {

    private static final String PREFIX = "jwt:revoked:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public TokenRevocationService(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** jti 를 만료 시각까지 폐기 목록에 등록. jti/만료 누락이거나 이미 만료면 무시. */
    public void revoke(String jti, Instant expiresAt) {
        if (jti == null || expiresAt == null) {
            return;
        }
        // exp 는 JwtProvider 가 주입 clock 으로 만든 절대시각 — ttl 도 동일 시계로 계산해야 정합(테스트 fixed-clock 포함).
        long ttl = Duration.between(clock.instant(), expiresAt).getSeconds();
        if (ttl <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(PREFIX + jti, "1", Duration.ofSeconds(ttl));
        } catch (RuntimeException e) {
            log.warn("토큰 폐기 등록 실패 (jti={})", jti, e);
        }
    }

    /** 폐기된 토큰인지. Redis 장애 시 false(미폐기) 로 fail-open. */
    public boolean isRevoked(String jti) {
        if (jti == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti));
        } catch (RuntimeException e) {
            log.warn("토큰 폐기 조회 실패 — 미폐기로 통과 (jti={})", jti, e);
            return false;
        }
    }
}
