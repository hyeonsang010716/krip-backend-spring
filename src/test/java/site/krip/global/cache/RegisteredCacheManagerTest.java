package site.krip.global.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import site.krip.global.cache.RegisteredCacheManager.Outcome;
import site.krip.global.config.AuthProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RegisteredCacheManager — 3-state(R/U/I) 음성 캐싱 + Redis 장애 시 fail-open(lookup null → DB 폴백),
 * cache 는 best-effort(예외 미전파).
 */
@DisplayName("가입상태 캐시 — Outcome 디코딩·TTL·fail-open")
class RegisteredCacheManagerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final RegisteredCacheManager cache =
            new RegisteredCacheManager(redis, new AuthProperties(null, null, 3600L));

    @Test
    @DisplayName("lookup 은 저장된 R/U/I 를 Outcome 으로 디코딩한다")
    void lookupDecodes() {
        // given
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("REGISTERED:u1")).thenReturn("R");
        when(ops.get("REGISTERED:u2")).thenReturn("U");
        when(ops.get("REGISTERED:u3")).thenReturn("I");

        // when & then
        assertThat(cache.lookup("u1")).isEqualTo(Outcome.REGISTERED);
        assertThat(cache.lookup("u2")).isEqualTo(Outcome.UNREGISTERED);
        assertThat(cache.lookup("u3")).isEqualTo(Outcome.INACTIVE);
    }

    @Test
    @DisplayName("미스/미상 값은 null (DB 폴백)")
    void lookupMissOrUnknown() {
        // given
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("REGISTERED:miss")).thenReturn(null);
        when(ops.get("REGISTERED:legacy")).thenReturn("1"); // 구버전 값

        // when & then
        assertThat(cache.lookup("miss")).isNull();
        assertThat(cache.lookup("legacy")).isNull();
    }

    @Test
    @DisplayName("exists() 는 REGISTERED 일 때만 true")
    void existsOnlyForRegistered() {
        // given
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("REGISTERED:r")).thenReturn("R");
        when(ops.get("REGISTERED:u")).thenReturn("U");

        // when & then
        assertThat(cache.exists("r")).isTrue();
        assertThat(cache.exists("u")).isFalse();
    }

    @Test
    @DisplayName("음성 결과는 짧은 TTL(60s), 양성은 기본 TTL 로 기록")
    void cacheUsesNegativeTtlForNonRegistered() {
        // given
        when(redis.opsForValue()).thenReturn(ops);

        cache.cache("u1", Outcome.REGISTERED);
        verify(ops).set(eq("REGISTERED:u1"), eq("R"), eq(Duration.ofSeconds(3600L)));

        cache.cache("u2", Outcome.UNREGISTERED);
        verify(ops).set(eq("REGISTERED:u2"), eq("U"), eq(Duration.ofSeconds(60L)));

        cache.cache("u3", Outcome.INACTIVE);
        verify(ops).set(eq("REGISTERED:u3"), eq("I"), eq(Duration.ofSeconds(60L)));
    }

    @Test
    @DisplayName("Redis 장애 시 lookup() 은 null (fail-open)")
    void lookupFailOpen() {
        // given
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));

        // when & then
        assertThat(cache.lookup("u1")).isNull();
        assertThat(cache.exists("u1")).isFalse();
    }

    @Test
    @DisplayName("Redis 장애 시 cache()/setFlag() 는 예외를 던지지 않는다 (best-effort)")
    void cacheSwallows() {
        // given
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));

        // when & then
        assertThatCode(() -> cache.setFlag("u1")).doesNotThrowAnyException();
        assertThatCode(() -> cache.cache("u2", Outcome.INACTIVE)).doesNotThrowAnyException();
    }
}
