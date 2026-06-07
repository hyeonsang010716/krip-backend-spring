package site.krip.domain.tripmate.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.repository.UserDetailInformRepository;
import site.krip.domain.tripmate.entity.TripmatePost;
import site.krip.domain.tripmate.entity.TripmatePostLike;
import site.krip.domain.tripmate.port.TripmateNotificationPort;
import site.krip.domain.tripmate.repository.TripmatePostLikeRepository;
import site.krip.global.common.exception.ApiException;
import site.krip.global.support.AfterCommit;

import java.util.List;

/**
 * 게시글 좋아요.
 *
 * <p>좋아요 추가는 INSERT 후 커밋 시점에 인박스 fan-out(best-effort).
 * 본인→본인 좋아요는 fan-out skip. 게시글 미존재/중복/미좋아요는 모두 400.
 */
@Service
public class TripmatePostLikeService {

    private final TripmatePostAccessGuard accessGuard;
    private final TripmatePostLikeRepository likeRepository;
    private final UserDetailInformRepository detailRepository;
    private final TripmateNotificationPort notificationPort;
    private final TransactionTemplate txTemplate;

    public TripmatePostLikeService(TripmatePostAccessGuard accessGuard,
                                   TripmatePostLikeRepository likeRepository,
                                   UserDetailInformRepository detailRepository,
                                   TripmateNotificationPort notificationPort,
                                   TransactionTemplate txTemplate) {
        this.accessGuard = accessGuard;
        this.likeRepository = likeRepository;
        this.detailRepository = detailRepository;
        this.notificationPort = notificationPort;
        this.txTemplate = txTemplate;
    }

    @Transactional(readOnly = true)
    public List<String> getLikedUserIds(String viewerId, String postId) {
        accessGuard.loadViewablePost(viewerId, postId);
        return likeRepository.findUserIdsByPostId(postId);
    }

    public long addLike(String userId, String postId) {
        Long likeCount = txTemplate.execute(status -> addLikeTx(userId, postId));
        return likeCount == null ? 0 : likeCount;
    }

    private long addLikeTx(String userId, String postId) {
        TripmatePost post = accessGuard.loadViewablePost(userId, postId);

        if (likeRepository.existsByUserIdAndPostId(userId, postId)) {
            throw ApiException.badRequest("이미 좋아요를 누른 게시글입니다.");
        }

        try {
            likeRepository.saveAndFlush(new TripmatePostLike(userId, postId));
        } catch (DataIntegrityViolationException e) {
            // 동시 클릭 race — "이미 좋아요" 와 동치로 일원화 (409 아닌 400)
            throw ApiException.badRequest("이미 좋아요를 누른 게시글입니다.");
        }
        long likeCount = likeRepository.countByPostId(postId);

        if (!post.getUserId().equals(userId)) {
            String recipientId = post.getUserId();
            String preview = post.getTitle();
            UserDetailInform detail = detailRepository.findById(userId).orElse(null);
            String actorName = detail != null ? detail.getUserName() : "";
            String actorImage = detail != null ? detail.getProfileImageUrl() : null;
            AfterCommit.run(() -> notificationPort.notifyTripmateLike(
                    recipientId, userId, actorName, actorImage, postId, preview));
        }
        return likeCount;
    }

    @Transactional
    public long removeLike(String userId, String postId) {
        accessGuard.loadViewablePost(userId, postId);
        if (likeRepository.deleteByUserIdAndPostId(userId, postId) == 0) {
            throw ApiException.badRequest("좋아요를 누르지 않은 게시글입니다.");
        }
        return likeRepository.countByPostId(postId);
    }
}
