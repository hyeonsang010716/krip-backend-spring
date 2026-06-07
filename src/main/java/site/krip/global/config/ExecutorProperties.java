package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비동기 executor 스레드 풀 설정.
 *
 * <p>{@code pushPoolSize}/{@code pushQueueCapacity}: 채팅 FCM 푸시(블로킹 Redis+FCM) 전용.
 * {@code recoverPoolSize}/{@code recoverQueueCapacity}: 접속 시 unread 백그라운드 복구(블로킹 Mongo) 전용 —
 * WS 하트비트 sweep 스케줄러와 격리한다. 포화 시 초과분은 드롭(공용 ForkJoinPool starvation 방지).
 */
@ConfigurationProperties(prefix = "krip.executor")
public record ExecutorProperties(
        int pushPoolSize,
        int pushQueueCapacity,
        int recoverPoolSize,
        int recoverQueueCapacity
) {
}
