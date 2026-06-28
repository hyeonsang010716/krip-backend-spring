package site.krip.domain.tripmate.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.port.UserProfileView;
import site.krip.domain.auth.port.UserQueryPort;
import site.krip.domain.tripmate.dto.response.LikedUsersResponse;
import site.krip.domain.tripmate.entity.TripmatePost;
import site.krip.domain.tripmate.entity.TripmatePostLike;
import site.krip.domain.tripmate.port.TripmateNotificationPort;
import site.krip.domain.tripmate.repository.TripmatePostLikeRepository;
import site.krip.global.common.exception.ApiException;
import site.krip.global.support.AfterCommit;
import site.krip.global.support.KeysetCursor;

import java.util.List;

/**
 * 게시글 좋아요.
 *
 * <p>좋아요 추가는 INSERT 후 커밋 시점에 인박스 fan-out(best-effort).
 * 본인→본인 좋아요는 fan-out skip. 게시글 미존재/중복/미좋아요는 모두 400.
 */
@Service
@RequiredArgsConstructor
public class TripmatePostLikeService {

    private static final int PAGE_SIZE = 30;

    private final TripmatePostAccessGuard accessGuard;
    private final TripmatePostLikeRepository likeRepository;
    private final UserQueryPort userQuery;
    private final TripmateNotificationPort notificationPort;
    private final TransactionTemplate txTemplate;

    @Transactional(readOnly = true)
    public LikedUsersResponse getLikedUsers(String viewerId, String postId, String cursor) {
        accessGuard.loadViewablePost(viewerId, postId);
        Pageable page = PageRequest.of(0, PAGE_SIZE + 1);
        List<TripmatePostLike> fetched = (cursor == null || cursor.isBlank())
                ? likeRepository.findLikesFirstPage(postId, page)
                : likesAfterCursor(postId, cursor, page);

        boolean hasMore = fetched.size() > PAGE_SIZE;
        List<TripmatePostLike> likes = hasMore ? fetched.subList(0, PAGE_SIZE) : fetched;
        List<String> userIds = likes.stream().map(TripmatePostLike::getUserId).toList();

        String nextCursor = hasMore
                ? KeysetCursor.encode(likes.get(likes.size() - 1).getCreatedAt(),
                        likes.get(likes.size() - 1).getUserId())
                : null;
        return new LikedUsersResponse(postId, userIds, nextCursor);
    }

    private List<TripmatePostLike> likesAfterCursor(String postId, String cursor, Pageable page) {
        KeysetCursor.Decoded c = KeysetCursor.decode(cursor);
        return likeRepository.findLikesAfterCursor(postId, c.sortKey(), c.id(), page);
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
            UserProfileView actor = userQuery.findProfile(userId).orElse(null);
            String actorName = actor != null ? actor.userName() : "";
            String actorImage = actor != null ? actor.profileImageUrl() : null;
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
