package site.krip.domain.auth.dto;

/** OAuth 콜백 처리 결과 — 가입 상태 + 프로필 + 발급된 JWT. 리다이렉트/쿠키 구성은 컨트롤러 몫. */
public record OAuthCallbackResult(SignupStatus status, String email, String name, String jwt) {
}
