package site.krip.domain.feed.port;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.port.FeedLikeCountPort;
import site.krip.domain.feed.repository.FeedPostLikeRepository;

/**
 * auth 의 {@link FeedLikeCountPort} 구현 — owner 의 모든 게시물이 받은 좋아요 총합(PRIVATE 포함).
 */
@Component
public class FeedLikeCountAdapter implements FeedLikeCountPort {

    private final FeedPostLikeRepository likeRepo;

    public FeedLikeCountAdapter(FeedPostLikeRepository likeRepo) {
        this.likeRepo = likeRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public long countTotalFeedLikes(String userId) {
        return likeRepo.countTotalForOwner(userId);
    }
}
