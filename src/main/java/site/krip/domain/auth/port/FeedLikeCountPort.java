package site.krip.domain.auth.port;

/**
 * 마이페이지 통계용 — 본인 피드 좋아요 총합 (feed 도메인 소유).
 * feed 도메인 포팅 전까지 stub 이 0 을 반환한다.
 */
public interface FeedLikeCountPort {

    long countTotalFeedLikes(String userId);
}
