package site.krip.global.chat;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import site.krip.global.config.ChatProperties;

/**
 * dedupe 전용 Redis 연결 — hot 키(DB 0)와 dedupe 키(DB 1)를 분리한다.
 *
 * <p>연결 정보는 {@link RedisConnectionDetails} 를 주입받아 재사용하고 DB 인덱스만 override 한다
 * (비밀번호·유저명·{@code url} 형식까지 hot 연결과 일치).
 *
 * <p>팩토리는 빈으로 노출하지 않고 직접 생성한다 — 빈으로 등록하면 Redis 자동구성이 backoff 되어
 * hot 팩토리가 사라지기 때문. {@code defaultCandidate=false} 라 {@code @Qualifier("dedupeRedisTemplate")} 로만 주입된다.
 * 수동 생성이라 {@link DisposableBean} 으로 종료 시 연결을 직접 닫는다.
 */
@Configuration
public class DedupeRedisConfig implements DisposableBean {

    private @Nullable LettuceConnectionFactory dedupeConnectionFactory;

    @Bean(defaultCandidate = false)
    public StringRedisTemplate dedupeRedisTemplate(RedisConnectionDetails connectionDetails,
                                                   ChatProperties chatProperties) {
        RedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(standalone.getHost(), standalone.getPort());
        config.setDatabase(chatProperties.dedupeRedisDatabase()); // hot 의 DB 대신 dedupe DB 로 격리
        if (connectionDetails.getUsername() != null) {
            config.setUsername(connectionDetails.getUsername());
        }
        if (connectionDetails.getPassword() != null) {
            config.setPassword(RedisPassword.of(connectionDetails.getPassword()));
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        this.dedupeConnectionFactory = factory;
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    /** 컨텍스트 종료 시 수동 생성한 dedupe 팩토리를 닫아 연결을 정리한다. */
    @Override
    public void destroy() {
        if (dedupeConnectionFactory != null) {
            dedupeConnectionFactory.destroy();
        }
    }
}
