package site.krip.domain.auth.oauth;

import org.jspecify.annotations.Nullable;
import site.krip.domain.auth.entity.OAuthProvider;

/** OAuth 제공자가 반환한 사용자 정보. email/name 은 제공자가 안 줄 수 있어 nullable. */
public record OAuthUser(
        String id,
        OAuthProvider provider,
        @Nullable String email,
        @Nullable String name
) {
}
