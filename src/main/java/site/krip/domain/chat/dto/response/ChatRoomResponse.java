package site.krip.domain.chat.dto.response;

import site.krip.domain.chat.entity.ChatRoomType;

import java.time.Instant;

/** 채팅방 응답. DIRECT 는 peer / GROUP 은 title. */
public record ChatRoomResponse(
        String chatRoomId,
        ChatRoomType type,
        String title,
        ChatRoomPeerResponse peer,
        LastMessagePreviewResponse lastMessage,
        int unreadCount,
        Instant lastMessageAt,
        Instant effectiveLastAt,
        boolean notificationMuted
) {
}
