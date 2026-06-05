package site.krip.domain.feed.repository;

import site.krip.domain.feed.entity.FeedPost;

/**
 * 리포지토리 → 서비스 row — post + 카운트 + viewer 좋아요 여부.
 */
public record FeedPostRow(FeedPost post, long likeCount, long commentCount, boolean liked) {

    /** {@link FeedPostRepository} 의 Object[] (FeedPost, Long, Long, Boolean) → row. */
    public static FeedPostRow fromTuple(Object[] t) {
        return new FeedPostRow(
                (FeedPost) t[0],
                ((Number) t[1]).longValue(),
                ((Number) t[2]).longValue(),
                Boolean.TRUE.equals(t[3]));
    }
}
