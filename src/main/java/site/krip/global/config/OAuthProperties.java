package site.krip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OAuth / 리다이렉트 설정.
 */
@ConfigurationProperties(prefix = "krip.oauth")
public record OAuthProperties(
        String redirectBaseUrl,
        String frontendUrl,
        String localFrontendUrl,
        String appDeepLink,
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
