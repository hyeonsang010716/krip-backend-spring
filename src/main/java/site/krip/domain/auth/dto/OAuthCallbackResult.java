package site.krip.domain.auth.dto;

import org.jspecify.annotations.Nullable;

/** OAuth 콜백 처리 결과 — 가입 상태 + 프로필 + 발급된 JWT. email/name 은 제공자 미제공 시 null. */
public record OAuthCallbackResult(SignupStatus status, @Nullable String email, @Nullable String name, String jwt) {
}
