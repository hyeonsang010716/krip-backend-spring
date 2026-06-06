package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비동기 executor 스레드 풀 설정.
 *
 * <p>{@code pushPoolSize}: 채팅 FCM 푸시(블로킹 Redis+FCM) 전용 고정 풀 크기.
 * {@code pushQueueCapacity}: 풀 포화 시 대기 큐 상한(초과분은 드롭) — 공용 ForkJoinPool starvation 방지.
 */
@ConfigurationProperties(prefix = "krip.executor")
public record ExecutorProperties(
        int pushPoolSize,
        int pushQueueCapacity
) {
}
