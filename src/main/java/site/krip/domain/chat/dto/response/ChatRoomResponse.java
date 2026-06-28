package site.krip.domain.chat.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.chat.entity.ChatRoomType;

import java.time.Instant;

/** 채팅방 응답. DIRECT 는 peer / GROUP 은 title. */
public record ChatRoomResponse(
        String chatRoomId,
        ChatRoomType type,
        @Nullable String title,
        @Nullable ChatRoomPeerResponse peer,
        @Nullable LastMessagePreviewResponse lastMessage,
        int unreadCount,
        @Nullable Instant lastMessageAt,
        Instant effectiveLastAt,
        boolean notificationMuted
) {
}
