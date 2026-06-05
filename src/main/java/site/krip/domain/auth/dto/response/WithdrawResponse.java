package site.krip.domain.auth.dto.response;

/** 탈퇴 요청 접수 응답. {@code scheduledPurgeAt} 은 ISO-8601 문자열. */
public record WithdrawResponse(
        String message,
        String scheduledPurgeAt
) {
}
