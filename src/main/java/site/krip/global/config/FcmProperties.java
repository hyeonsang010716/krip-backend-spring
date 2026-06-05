package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FCM 설정.
 *
 * <p>{@code credentialsPath}: Firebase 서비스 계정 JSON 경로. 파일이 없거나 {@code enabled=false} 면
 * 발송은 비활성(no-op) — 토큰 등록/뮤트/인박스는 정상 동작한다.
 */
@ConfigurationProperties(prefix = "krip.fcm")
public record FcmProperties(
        boolean enabled,
        String credentialsPath
) {
}
