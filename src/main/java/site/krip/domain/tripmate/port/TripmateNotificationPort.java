package site.krip.domain.tripmate.port;

/**
 * 알림(notification/inbox) 도메인 연동 포트. 좋아요 fan-out + 게시글 삭제 cascade.
 */
public interface TripmateNotificationPort {

    /** 좋아요 알림 fan-out (본인→본인은 호출측에서 skip). */
    void notifyTripmateLike(String recipientId, String actorId, String actorName,
                            String actorProfileImageUrl, String postId, String postPreview);

    /** 게시글 삭제 시 해당 게시글의 좋아요 알림 일괄 soft hide. */
    void cascadePostDeleted(String postId);
}
