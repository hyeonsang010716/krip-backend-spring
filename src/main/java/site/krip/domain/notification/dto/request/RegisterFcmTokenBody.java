package site.krip.domain.notification.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** FCM 토큰 등록 요청. */
public record RegisterFcmTokenBody(
        @NotNull @Size(min = 1, max = 512) String token
) {
}
