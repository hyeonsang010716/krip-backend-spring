package site.krip.domain.chat.dto.response;

import java.time.Instant;

/** 메시지 편집 응답. */
public record EditMessageResponse(
        String messageId,
        Object content,
        Instant editedAt
) {
}
