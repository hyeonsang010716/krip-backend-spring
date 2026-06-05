package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 관련 설정.
 */
@ConfigurationProperties(prefix = "krip.auth")
public record AuthProperties(
        String accessToken,
        Jwt jwt,
        long registeredCacheTtlSeconds
) {
    public record Jwt(
            String secret,
            int expirationDays,
            String cookieName
    ) {
        public long expirationSeconds() {
            return (long) expirationDays * 24 * 60 * 60;
        }
    }
}
