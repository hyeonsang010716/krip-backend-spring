package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 스케줄링 스레드 풀 크기.
 *
 * <p>{@code poolSize}: 전역 {@code @Scheduled} 잡(reconcile/heartbeat/purge)용.
 * {@code chatWsPoolSize}: 채팅 WS 세션 하트비트/unread 복구 전용(블로킹 잡과 격리).
 */
@ConfigurationProperties(prefix = "krip.scheduling")
public record SchedulingProperties(
        int poolSize,
        int chatWsPoolSize
) {
}
