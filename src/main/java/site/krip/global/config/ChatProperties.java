package site.krip.global.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 채팅 도메인 설정.
 *
 * <p>{@code fanoutMode}: {@code in_process}(단일 프로세스 직배송) 또는
 * {@code redis_stream}(다중 노드 Redis Stream). {@code dedupeRedisDatabase}: dedupe 키 격리용 Redis DB(hot 과 분리).
 *
 * <p>{@code wsSendTimeLimitMs}/{@code wsSendBufferBytes}: WS 전송 시간·버퍼 상한(느린 소켓이 전송 스레드를
 * 오래 묶지 못하게 데코레이터로 강제 — 초과 시 세션 종료). {@code deliverySessionMaxQueued}: fan-out 전송을
 * 세션별 직렬 실행기로 offload 할 때 세션당 대기 한도(초과 시 그 전달만 드롭 — 폴/송신 스레드 비차단).
 */
@Validated
@ConfigurationProperties(prefix = "krip.chat")
public record ChatProperties(
        @NotBlank String fanoutMode,
        @NotBlank String nodeId,
        @PositiveOrZero int dedupeRedisDatabase,
        @Positive int wsSendTimeLimitMs,
        @Positive int wsSendBufferBytes,
        @Positive int deliverySessionMaxQueued
) {
    public boolean isMultiNode() {
        return "redis_stream".equals(fanoutMode);
    }
}
