package site.krip.domain.notification.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** FCM 토큰 해제 요청. */
public record UnregisterFcmTokenBody(
        @NotNull @Size(min = 1, max = 512) String token
) {
}
