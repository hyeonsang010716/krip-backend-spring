package site.krip.global.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 인증 관련 설정.
 */
@Validated
@ConfigurationProperties(prefix = "krip.auth")
public record AuthProperties(
        @NotBlank String accessToken,
        @Valid Jwt jwt,
        @Positive long registeredCacheTtlSeconds
) {
    public record Jwt(
            @NotBlank String secret,
            @Positive int expirationDays,
            @NotBlank String cookieName
    ) {
        public long expirationSeconds() {
            return (long) expirationDays * 24 * 60 * 60;
        }
    }
}
