package site.krip.global.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import site.krip.global.config.AuthProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RegisteredCacheManager — Redis 장애 시 exists 는 fail-open(false → DB 폴백), setFlag 는 best-effort(예외 미전파).
 */
class RegisteredCacheManagerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RegisteredCacheManager cache =
            new RegisteredCacheManager(redis, new AuthProperties(null, null, 60L));

    @Test
    @DisplayName("Redis 장애 시 exists() 는 false (fail-open)")
    void existsFailOpen() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertThat(cache.exists("u1")).isFalse();
    }

    @Test
    @DisplayName("Redis 장애 시 setFlag() 는 예외를 던지지 않는다 (best-effort)")
    void setFlagSwallows() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
        assertThatCode(() -> cache.setFlag("u1")).doesNotThrowAnyException();
    }
}
