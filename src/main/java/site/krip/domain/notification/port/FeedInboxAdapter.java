package site.krip.domain.notification.port;

import org.springframework.stereotype.Component;
import site.krip.domain.feed.port.FeedInboxPort;
import site.krip.domain.notification.document.TargetType;
import site.krip.domain.notification.service.InboxService;

/**
 * feed 의 {@link FeedInboxPort} 실제 구현 — 좋아요/댓글 알림 fan-out + 게시글 삭제 cascade.
 */
@Component
public class FeedInboxAdapter implements FeedInboxPort {

    private final InboxService inboxService;

    public FeedInboxAdapter(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @Override
    public void notifyFeedLike(String recipientId, String actorId, String actorName,
                               String actorProfileImageUrl, String postId, String postPreview) {
        inboxService.notifyFeedLike(recipientId, actorId, actorName, actorProfileImageUrl, postId, postPreview);
    }

    @Override
    public void notifyFeedComment(String recipientId, String actorId, String actorName,
                                  String actorProfileImageUrl, String postId, String postPreview,
                                  String commentId, String commentContent) {
        inboxService.notifyFeedComment(recipientId, actorId, actorName, actorProfileImageUrl,
                postId, postPreview, commentId, commentContent);
    }

    @Override
    public void cascadeFeedPostDeleted(String postId) {
        inboxService.cascadePostDeleted(TargetType.FEED_POST.getValue(), postId);
    }
}
