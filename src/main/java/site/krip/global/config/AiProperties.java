package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 서비스(FastAPI) 연동 설정. {@code enabled=false} 면 호출을 막고 503 으로 응답하고, read 타임아웃은
 * LLM 추론 지연을 고려해 길게 잡는다. 서킷 브레이커는 연속 실패 시 호출을 단락(fast-fail)해 AI 장애가
 * 워커 풀 고갈로 번지는 것을 막는다.
 */
@ConfigurationProperties(prefix = "krip.ai")
public record AiProperties(
        boolean enabled,
        String serviceUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        int circuitFailureThreshold,
        int circuitOpenMs
) {
}
