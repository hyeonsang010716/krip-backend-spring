package site.krip.domain.chat.dto.response;

import org.bson.Document;

import java.time.Instant;

/**
 * 채팅 메시지 응답. content 는 type 별 다형(삭제 시 null).
 */
public record ChatMessageResponse(
        String messageId,
        String chatRoomId,
        long serverSeq,
        String senderId,
        String type,
        Object content,
        Instant createdAt,
        Instant editedAt,
        Instant deletedAt
) {
    public static ChatMessageResponse fromDoc(Document d) {
        java.util.Date deletedAt = d.getDate("deleted_at");
        java.util.Date createdAt = d.getDate("created_at");
        java.util.Date editedAt = d.getDate("edited_at");
        String type = d.getString("type");
        Object content = deletedAt == null ? d.get("content") : null;
        return new ChatMessageResponse(
                d.getString("_id"),
                d.getString("chat_room_id"),
                ((Number) d.get("server_seq")).longValue(),
                d.getString("sender_id"),
                type != null ? type : "text",
                content,
                createdAt != null ? createdAt.toInstant() : null,
                editedAt != null ? editedAt.toInstant() : null,
                deletedAt != null ? deletedAt.toInstant() : null);
    }
}
