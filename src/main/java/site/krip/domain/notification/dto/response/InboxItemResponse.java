package site.krip.domain.notification.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.notification.document.InboxItem;
import site.krip.domain.notification.document.InboxItemType;
import site.krip.domain.notification.document.TargetType;

import java.time.Instant;

/**
 * 인박스 단건 응답. snapshot 필드는 발생 시점 값.
 * readAt 존재 여부를 isRead 로 평탄화. comment* 는 FEED_COMMENT 만 값이 있고 그 외 null.
 */
public record InboxItemResponse(
        String inboxItemId,
        InboxItemType type,
        String actorId,
        String actorName,
        @Nullable String actorProfileImageUrl,
        TargetType targetType,
        String targetId,
        @Nullable String commentId,
        String targetPreview,
        @Nullable String commentPreview,
        boolean isRead,
        Instant createdAt
) {
    public static InboxItemResponse from(InboxItem i) {
        return new InboxItemResponse(
                i.getId(), i.getType(), i.getActorId(), i.getActorName(), i.getActorProfileImageUrl(),
                i.getTargetType(), i.getTargetId(), i.getCommentId(), i.getTargetPreview(),
                i.getCommentPreview(), i.getReadAt() != null, i.getCreatedAt());
    }
}
