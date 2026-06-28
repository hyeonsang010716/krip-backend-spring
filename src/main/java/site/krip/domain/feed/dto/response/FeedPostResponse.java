package site.krip.domain.feed.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.feed.entity.FeedVisibility;
import site.krip.domain.feed.repository.FeedPostRow;

import java.time.Instant;

/** 피드 게시물 단건 응답. 카운트는 응답 시점 스냅샷. */
public record FeedPostResponse(
        String postId,
        String userId,
        FeedVisibility visibility,
        @Nullable String caption,
        String originalUrl,
        String thumbnailSmallUrl,
        String thumbnailMediumUrl,
        long likeCount,
        long commentCount,
        boolean isLiked,
        Instant createdAt,
        Instant updatedAt
) {
    public static FeedPostResponse from(FeedPostRow row) {
        var p = row.post();
        return new FeedPostResponse(
                p.getPostId(), p.getUserId(), p.getVisibility(), p.getCaption(),
                p.getOriginalUrl(), p.getThumbnailSmallUrl(), p.getThumbnailMediumUrl(),
                row.likeCount(), row.commentCount(), row.liked(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
