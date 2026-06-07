package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FCM 설정.
 *
 * <p>{@code credentialsPath}: Firebase 서비스 계정 JSON 경로. 파일이 없거나 {@code enabled=false} 면
 * 발송은 비활성(no-op) — 토큰 등록/뮤트/인박스는 정상 동작한다.
 * {@code connectTimeoutMs}/{@code readTimeoutMs}: Firebase HTTP 전송 타임아웃 — FCM 행(hang) 시
 * push 워커 스레드가 무한 점유되는 것을 막는다.
 * {@code circuitFailureThreshold}/{@code circuitOpenMs}: 전송 연속 실패가 임계치에 도달하면
 * cooldown 동안 발송을 단락(fast-fail)해 장애 시 워커 풀 고갈을 막는다.
 */
@ConfigurationProperties(prefix = "krip.fcm")
public record FcmProperties(
        boolean enabled,
        String credentialsPath,
        int connectTimeoutMs,
        int readTimeoutMs,
        int circuitFailureThreshold,
        int circuitOpenMs
) {
}
