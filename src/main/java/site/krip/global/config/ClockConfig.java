package site.krip.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 시간 소스 빈 — 시간 의존 로직(토큰 만료 등)을 테스트에서 고정 가능하게 한다.
 *
 * <p>운영은 UTC 시스템 시계, 테스트는 {@code Clock.fixed(...)} 주입.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
