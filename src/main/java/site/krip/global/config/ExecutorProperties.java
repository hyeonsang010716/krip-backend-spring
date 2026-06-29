package site.krip.global.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 비동기 executor 스레드 풀 설정.
 *
 * <p>{@code pushPoolSize}/{@code pushQueueCapacity}: 채팅 FCM 푸시(블로킹 Redis+FCM) 전용.
 * {@code recoverPoolSize}/{@code recoverQueueCapacity}: 접속 시 unread 백그라운드 복구(블로킹 Mongo) 전용 —
 * WS 하트비트 sweep 스케줄러와 격리한다. 포화 시 초과분은 드롭(공용 ForkJoinPool starvation 방지).
 * {@code imageProcess*}: 이미지 decode/resize+업로드 동시 처리 한도(포화 시 429). {@code imageUpload*}: variant S3 병렬 업로드 풀.
 * {@code chatOp*}: WS op(send/read) 처리를 컨테이너 I/O 스레드에서 격리. {@code chatOpSessionMaxQueued}는
 * 세션당 대기 한도(초과 시 server_busy 백프레셔).
 * {@code chatDelivery*}: redis_stream fan-out 전송을 폴 스레드에서 분리하는 전용 풀(인바운드 chatOp 와 격리해
 * 아웃바운드 폭주가 인바운드를 굶기지 않게 한다). 포화 시 해당 전달만 드롭(best-effort).
 */
@Validated
@ConfigurationProperties(prefix = "krip.executor")
public record ExecutorProperties(
        @Positive int pushPoolSize,
        @Positive int pushQueueCapacity,
        @Positive int recoverPoolSize,
        @Positive int recoverQueueCapacity,
        @Positive int imageProcessPoolSize,
        @Positive int imageProcessQueueCapacity,
        @Positive int imageUploadPoolSize,
        @Positive int imageUploadQueueCapacity,
        @Positive int chatOpPoolSize,
        @Positive int chatOpQueueCapacity,
        @Positive int chatOpSessionMaxQueued,
        @Positive int chatDeliveryPoolSize,
        @Positive int chatDeliveryQueueCapacity
) {
}
