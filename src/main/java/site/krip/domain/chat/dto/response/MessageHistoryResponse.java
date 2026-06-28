package site.krip.domain.chat.dto.response;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 메시지 히스토리 응답. next_cursor = 마지막 메시지의 server_seq (has_more=false 면 null).
 */
public record MessageHistoryResponse(
        List<ChatMessageResponse> messages,
        boolean hasMore,
        @Nullable Long nextCursor
) {
}
