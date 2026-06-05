package site.krip.domain.auth.oauth;

import site.krip.domain.auth.entity.OAuthProvider;

/** OAuth 제공자가 반환한 사용자 정보. */
public record OAuthUser(
        String id,
        OAuthProvider provider,
        String email,
        String name
) {
}
