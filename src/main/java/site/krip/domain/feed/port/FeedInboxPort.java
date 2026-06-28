package site.krip.domain.feed.port;

import org.jspecify.annotations.Nullable;

/**
 * 피드 좋아요/댓글 알림 인박스 fan-out + 게시글 삭제 cascade (notification 도메인 소유).
 * 모두 트랜잭션 밖 best-effort 로 호출되며 본인→본인은 skip.
 */
public interface FeedInboxPort {

    void notifyFeedLike(String recipientId, String actorId, String actorName,
                        @Nullable String actorProfileImageUrl, String postId, String postPreview);

    void notifyFeedComment(String recipientId, String actorId, String actorName,
                           @Nullable String actorProfileImageUrl, String postId, String postPreview,
                           String commentId, String commentContent);

    /** 게시글 삭제 시 해당 게시글의 모든 알림 soft hide. */
    void cascadeFeedPostDeleted(String postId);
}
