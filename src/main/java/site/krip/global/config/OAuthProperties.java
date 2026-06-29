package site.krip.global.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * OAuth / 리다이렉트 설정.
 */
@Validated
@ConfigurationProperties(prefix = "krip.oauth")
public record OAuthProperties(
        @NotBlank String redirectBaseUrl,
        @NotBlank String frontendUrl,
        @NotBlank String localFrontendUrl,
        @NotBlank String appDeepLink,
        Google google
) {
    public record Google(
            String clientId,
            String clientSecret
    ) {
    }

    /** 웹 콜백 redirect_uri: {base}/api/auth/login */
    public String webRedirectUri() {
        return redirectBaseUrl + "/api/auth/login";
    }

    /** 앱 콜백 redirect_uri: {base}/api/auth/login/app */
    public String appRedirectUri() {
        return redirectBaseUrl + "/api/auth/login/app";
    }
}
