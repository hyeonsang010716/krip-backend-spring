package site.krip.global.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI 서비스(FastAPI) 연동 설정. {@code enabled=false} 면 호출을 막고 503 으로 응답하고,
 * read 타임아웃은 LLM 추론 지연을 고려해 길게 잡는다.
 * 서킷 브레이커는 연속 실패 시 호출을 단락(fast-fail)해 AI 장애가 워커 풀 고갈로 번지는 것을 막는다.
 * {@code maxConcurrency}: 동시 호출 상한(bulkhead) — 느린 LLM 호출이 Tomcat 풀을 고갈시키지 않게 격리, 초과 시 503.
 */
@Validated
@ConfigurationProperties(prefix = "krip.ai")
public record AiProperties(
        boolean enabled,
        @NotBlank String serviceUrl,
        @Positive int connectTimeoutMs,
        @Positive int readTimeoutMs,
        @Positive int circuitFailureThreshold,
        @Positive int circuitOpenMs,
        @Positive int maxConcurrency
) {
}
