package site.krip.global.auth.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TokenRevocationService — TTL 계산이 주입 Clock 기준인지 검증.
 * exp 는 JwtProvider 가 주입 clock 으로 만든 절대시각이므로, ttl 도 같은 시계로 계산해야 한다.
 */
class TokenRevocationServiceTest {

    private static final Instant FIXED = Instant.parse("2020-01-01T00:00:00Z");

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final TokenRevocationService svc =
            new TokenRevocationService(redis, Clock.fixed(FIXED, ZoneOffset.UTC));

    @Test
    @DisplayName("exp 가 주입 clock 기준 미래면 ttl 을 그 시계로 계산해 폐기 등록한다 (wall-clock 과 무관)")
    void revokeUsesInjectedClock() {
        when(redis.opsForValue()).thenReturn(ops);

        // exp = clock + 60s. wall-clock(Instant.now())로 계산하면 2020 기준이라 음수 → 구버전은 스킵됐다.
        svc.revoke("jti1", FIXED.plusSeconds(60));

        verify(ops).set(eq("jwt:revoked:jti1"), eq("1"), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("exp 가 주입 clock 기준 이미 만료면 등록을 스킵한다")
    void revokeSkipsWhenAlreadyExpired() {
        svc.revoke("jti2", FIXED.minusSeconds(10));

        verify(ops, never()).set(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("jti/exp 누락이면 Redis 를 건드리지 않는다")
    void revokeNoopOnNullInput() {
        svc.revoke(null, FIXED.plusSeconds(60));
        svc.revoke("jti3", null);

        verifyNoInteractions(redis);
    }
}
