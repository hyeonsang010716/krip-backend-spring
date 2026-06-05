package site.krip.domain.notification.dto.request;

import jakarta.validation.constraints.NotNull;

/** 알림 차단 토글 요청. true=차단, false=해제. */
public record MuteToggleBody(
        @NotNull Boolean muted
) {
}
