package site.krip.domain.tripmate.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.repository.UserDetailInformRepository;
import site.krip.domain.tripmate.dto.AddLikePayload;
import site.krip.domain.tripmate.entity.TripmatePost;
import site.krip.domain.tripmate.entity.TripmatePostLike;
import site.krip.domain.tripmate.exception.PostNotFoundException;
import site.krip.domain.tripmate.port.TripmateNotificationPort;
import site.krip.domain.tripmate.repository.TripmatePostLikeRepository;
import site.krip.domain.tripmate.repository.TripmatePostRepository;
import site.krip.global.common.exception.ApiException;

import java.util.List;

/**
 * 게시글 좋아요.
 *
 * <p>좋아요 추가는 트랜잭션 안 INSERT → 트랜잭션 밖 인박스 fan-out(best-effort).
 * 본인→본인 좋아요는 fan-out skip. 게시글 미존재/중복/미좋아요는 모두 400.
 */
@Service
public class TripmatePostLikeService {

    private final TripmatePostRepository postRepository;
    private final TripmatePostLikeRepository likeRepository;
    private final UserDetailInformRepository detailRepository;
    private final TripmateNotificationPort notificationPort;
    private final TransactionTemplate txTemplate;

    public TripmatePostLikeService(TripmatePostRepository postRepository,
                                   TripmatePostLikeRepository likeRepository,
                                   UserDetailInformRepository detailRepository,
                                   TripmateNotificationPort notificationPort,
                                   TransactionTemplate txTemplate) {
        this.postRepository = postRepository;
        this.likeRepository = likeRepository;
        this.detailRepository = detailRepository;
        this.notificationPort = notificationPort;
        this.txTemplate = txTemplate;
    }

    @Transactional(readOnly = true)
    public List<String> getLikedUserIds(String postId) {
        if (!postRepository.existsById(postId)) {
            throw new PostNotFoundException();
        }
        return likeRepository.findUserIdsByPostId(postId);
    }

    public long addLike(String userId, String postId) {
        AddLikePayload payload = txTemplate.execute(status -> addLikeTx(userId, postId));
        if (payload != null && !payload.recipientId().equals(userId)) {
            notificationPort.notifyTripmateLike(
                    payload.recipientId(), userId, payload.actorName(),
                    payload.actorProfileImageUrl(), postId, payload.postPreview());
        }
        return payload == null ? 0 : payload.likeCount();
    }

    private AddLikePayload addLikeTx(String userId, String postId) {
        TripmatePost post = postRepository.findById(postId)
                .orElseThrow(() -> ApiException.badRequest("존재하지 않는 게시글입니다."));

        if (likeRepository.existsByUserIdAndPostId(userId, postId)) {
            throw ApiException.badRequest("이미 좋아요를 누른 게시글입니다.");
        }

        likeRepository.save(new TripmatePostLike(userId, postId));
        long likeCount = likeRepository.countByPostId(postId);

        // 본인→본인 — 호출부에서 fan-out skip (더미값)
        if (post.getUserId().equals(userId)) {
            return new AddLikePayload(likeCount, post.getUserId(), "", null, null);
        }

        UserDetailInform detail = detailRepository.findById(userId).orElse(null);
        return new AddLikePayload(
                likeCount,
                post.getUserId(),
                detail != null ? detail.getUserName() : "",
                detail != null ? detail.getProfileImageUrl() : null,
                post.getTitle());
    }

    @Transactional
    public long removeLike(String userId, String postId) {
        if (!likeRepository.existsByUserIdAndPostId(userId, postId)) {
            throw ApiException.badRequest("좋아요를 누르지 않은 게시글입니다.");
        }
        likeRepository.deleteByUserIdAndPostId(userId, postId);
        return likeRepository.countByPostId(postId);
    }
}
