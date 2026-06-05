package site.krip.domain.chat.dto.response;

import java.util.List;

/** 방 리스트 응답. */
public record ChatRoomListResponse(
        List<ChatRoomResponse> items,
        String nextCursor
) {
}
