package site.krip.domain.auth.dto;

/** OAuth 콜백 후 가입 상태 판정 결과. */
public record SignupResult(String userId, SignupStatus status) {
}
