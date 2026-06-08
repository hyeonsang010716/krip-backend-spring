package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비동기 executor 스레드 풀 설정.
 *
 * <p>{@code pushPoolSize}/{@code pushQueueCapacity}: 채팅 FCM 푸시(블로킹 Redis+FCM) 전용.
 * {@code recoverPoolSize}/{@code recoverQueueCapacity}: 접속 시 unread 백그라운드 복구(블로킹 Mongo) 전용 —
 * WS 하트비트 sweep 스케줄러와 격리한다. 포화 시 초과분은 드롭(공용 ForkJoinPool starvation 방지).
 * {@code imageProcess*}: 이미지 decode/resize+업로드 동시 처리 한도(포화 시 429). {@code imageUpload*}: variant S3 병렬 업로드 풀.
 * {@code chatOp*}: WS op(send/read) 처리를 컨테이너 I/O 스레드에서 격리. {@code chatOpSessionMaxQueued}는
 * 세션당 대기 한도(초과 시 server_busy 백프레셔).
 */
@ConfigurationProperties(prefix = "krip.executor")
public record ExecutorProperties(
        int pushPoolSize,
        int pushQueueCapacity,
        int recoverPoolSize,
        int recoverQueueCapacity,
        int imageProcessPoolSize,
        int imageProcessQueueCapacity,
        int imageUploadPoolSize,
        int imageUploadQueueCapacity,
        int chatOpPoolSize,
        int chatOpQueueCapacity,
        int chatOpSessionMaxQueued
) {
}
