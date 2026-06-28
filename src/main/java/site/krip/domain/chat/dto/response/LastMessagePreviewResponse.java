package site.krip.domain.chat.dto.response;

import org.bson.Document;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 방 리스트 미리보기용 최신 메시지 요약. content 는 type 별 다형(삭제된 메시지는 null).
 */
public record LastMessagePreviewResponse(
        String messageId,
        long serverSeq,
        String senderId,
        String type,
        @Nullable Object content,
        @Nullable Instant createdAt
) {
    public static LastMessagePreviewResponse fromDoc(Document d) {
        java.util.Date deletedAt = d.getDate("deleted_at");
        Object content = deletedAt == null ? d.get("content") : null;
        String type = d.getString("type");
        java.util.Date createdAt = d.getDate("created_at");
        return new LastMessagePreviewResponse(
                d.getString("_id"),
                ((Number) Objects.requireNonNull(d.get("server_seq"))).longValue(),
                d.getString("sender_id"),
                type != null ? type : "text",
                content,
                createdAt != null ? createdAt.toInstant() : null);
    }
}
