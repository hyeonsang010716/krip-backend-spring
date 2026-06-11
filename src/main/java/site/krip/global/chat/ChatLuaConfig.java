package site.krip.global.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/**
 * 채팅 Lua 스크립트 빈.
 *
 * <p>{@code StringRedisTemplate.execute} 가 EVALSHA 우선 / NOSCRIPT 시 EVAL fallback 을
 * 자동 처리하므로 SHA 캐싱은 직접 손대지 않는다. seq 계열은 정수(Long), 세션 한도는 evict 목록(List) 반환.
 */
@Configuration
public class ChatLuaConfig {

    private static RedisScript<Long> load(String file) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/chat/" + file)));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> incrFastScript() {
        return load("incr_fast.lua");
    }

    @Bean
    public RedisScript<Long> recoverAndIncrScript() {
        return load("recover_and_incr.lua");
    }

    @Bean
    public RedisScript<Long> forceJumpScript() {
        return load("force_jump.lua");
    }

    @Bean
    public RedisScript<Long> incrWithTtlScript() {
        return load("incr_with_ttl.lua");
    }

    /** 세션 3키 원자 세팅 — HSET/EXPIRE 분리로 인한 TTL 없는 좀비 해시 누수 차단. */
    @Bean
    public RedisScript<Long> setSessionScript() {
        return load("set_session.lua");
    }

    /** token_jti 갱신 — 키 존재 시에만, HSET 의 좀비 해시 부활 방지. */
    @Bean
    public RedisScript<Long> updateTokenJtiScript() {
        return load("update_token_jti.lua");
    }

    /** SADD + EXPIRE 원자화 — 멤버/차단 캐시의 TTL 없는 좀비 set 누수 차단. */
    @Bean
    public RedisScript<Long> saddWithTtlScript() {
        return load("sadd_with_ttl.lua");
    }

    /** 세션 한도 강제 — evict 된 session_id 목록 반환(List). */
    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public RedisScript<List> enforceSessionLimitScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/chat/enforce_session_limit.lua")));
        script.setResultType(List.class);
        return script;
    }
}
