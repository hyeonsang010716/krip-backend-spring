package site.krip.domain.notification.dto.response;

import java.time.Instant;

/** FCM 토큰 등록 응답. 토큰 문자열은 노출하지 않음. */
public record FcmTokenResponse(
        String fcmTokenId,
        Instant createdAt
) {
}
