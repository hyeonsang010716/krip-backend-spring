package site.krip.domain.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 메시지 편집 요청. */
public record EditMessageBody(
        @NotNull @Size(min = 1, max = 2000) String content
) {
}
