package site.krip.domain.auth.oauth;

import site.krip.domain.auth.entity.OAuthProvider;

/** OAuth 클라이언트. */
public interface OAuthClient {

    OAuthProvider provider();

    /** 인증 페이지 URL 생성 (state + prompt=select_account). */
    String buildAuthorizationUrl(OAuthConfig config, String state);

    /** authorization code → access token 교환. */
    String exchangeCodeForToken(OAuthConfig config, String code);

    /** access token → 사용자 정보 조회. */
    OAuthUser fetchUserInfo(OAuthConfig config, String accessToken);
}
