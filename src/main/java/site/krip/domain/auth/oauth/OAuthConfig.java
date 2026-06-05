package site.krip.domain.auth.oauth;

/**
 * OAuth 제공자 설정. {@code redirectUri} 는 base 이며 콜백은 {@code /callback} 이 붙는다.
 */
public record OAuthConfig(
        String clientId,
        String clientSecret,
        String authorizeUrl,
        String tokenUrl,
        String userinfoUrl,
        String redirectUri,
        String scope
) {
    /** 실제 redirect_uri — base + /callback (web/app 공통). */
    public String callbackRedirectUri() {
        return redirectUri + "/callback";
    }
}
