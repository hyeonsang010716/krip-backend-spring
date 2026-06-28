package site.krip.domain.feed.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.feed.entity.FeedPostComment;

import java.time.Instant;

/** 댓글 단건 응답 — 작성자 프로필 포함. */
public record CommentResponse(
        String commentId,
        String postId,
        String userId,
        String userName,
        @Nullable String profileImageUrl,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
    public static CommentResponse of(FeedPostComment c, String userName, @Nullable String profileImageUrl) {
        return new CommentResponse(c.getCommentId(), c.getPostId(), c.getUserId(),
                userName != null ? userName : "", profileImageUrl,
                c.getContent(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
