package site.krip.global.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * 채팅 Lua 스크립트 빈.
 *
 * <p>{@code StringRedisTemplate.execute} 가 EVALSHA 우선 / NOSCRIPT 시 EVAL fallback 을
 * 자동 처리하므로 SHA 캐싱은 직접 손대지 않는다. 모든 스크립트는 정수(Long)를 반환한다.
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
}
