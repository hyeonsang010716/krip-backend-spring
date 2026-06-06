package site.krip.domain.feed.repository;

import site.krip.domain.feed.entity.FeedPost;

/**
 * 리포지토리 → 서비스 row — post + 카운트 + viewer 좋아요 여부.
 */
public record FeedPostRow(FeedPost post, long likeCount, long commentCount, boolean liked) {
}
