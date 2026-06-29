package site.krip.global.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 스케줄링 스레드 풀 크기.
 *
 * <p>{@code poolSize}: 전역 {@code @Scheduled} 잡(reconcile/heartbeat/purge)용.
 * {@code chatWsPoolSize}: 채팅 WS 세션 하트비트/unread 복구 전용(블로킹 잡과 격리).
 */
@Validated
@ConfigurationProperties(prefix = "krip.scheduling")
public record SchedulingProperties(
        @Positive int poolSize,
        @Positive int chatWsPoolSize
) {
}
