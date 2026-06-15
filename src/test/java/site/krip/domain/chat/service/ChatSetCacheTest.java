package site.krip.domain.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ChatSetCache#saddWithTtl} 단위 테스트 — 빈 멤버 가드(스크립트 SADD 0-인자 abort 방지)와 정상 ARGV 구성.
 */
class ChatSetCacheTest {

    private StringRedisTemplate redis;
    private RedisScript<Long> script;
    private ChatSetCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        script = mock(RedisScript.class);
        cache = new ChatSetCache(redis, script);
    }

    @Test
    @DisplayName("빈 멤버는 no-op — Redis 에 전혀 접근하지 않는다(SADD 0-인자 크래시 방지)")
    void emptyMembersIsNoOp() {
        cache.saddWithTtl("room:members:R", 60, List.of());
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("멤버가 있으면 [ttl, members...] 를 ARGV 로 스크립트 실행")
    void nonEmptyExecutesScript() {
        cache.saddWithTtl("room:members:R", 60, List.of("a", "b"));
        verify(redis).execute(eq(script), eq(List.of("room:members:R")), eq("60"), eq("a"), eq("b"));
    }
}
