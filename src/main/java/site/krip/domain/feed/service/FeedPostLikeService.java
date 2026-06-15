package site.krip.domain.feed.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.port.UserProfileView;
import site.krip.domain.auth.port.UserQueryPort;
import site.krip.domain.feed.dto.response.LikedUserItem;
import site.krip.domain.feed.dto.response.LikedUsersResponse;
import site.krip.domain.feed.entity.FeedPost;
import site.krip.domain.feed.entity.FeedPostLike;
import site.krip.domain.feed.port.FeedInboxPort;
import site.krip.domain.feed.repository.FeedPostLikeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import site.krip.global.common.exception.ApiException;
import site.krip.global.support.AfterCommit;
import site.krip.global.support.KeysetCursor;

import java.util.List;
import java.util.Map;

/**
 * 피드 좋아요 서비스.
 *
 * <p>모든 진입점이 가시성 검증 우선 — 차단·visibility 미충족 모두 404(정보 누출 회피). 본인 글 본인 좋아요 허용.
 * 중복 add / 미존재 remove 는 400. add 인박스 fan-out 은 트랜잭션 커밋 후 best-effort(본인→본인 skip).
 */
@Service
public class FeedPostLikeService {

    private static final Logger log = LoggerFactory.getLogger(FeedPostLikeService.class);
    private static final int PAGE_SIZE = 30;

    private final FeedAccessService access;
    private final FeedPostLikeRepository likeRepo;
    private final UserQueryPort userQuery;
    private final FeedInboxPort inboxPort;
    private final TransactionTemplate txTemplate;

    public FeedPostLikeService(FeedAccessService access, FeedPostLikeRepository likeRepo,
                               UserQueryPort userQuery, FeedInboxPort inboxPort,
                               TransactionTemplate txTemplate) {
        this.access = access;
        this.likeRepo = likeRepo;
        this.userQuery = userQuery;
        this.inboxPort = inboxPort;
        this.txTemplate = txTemplate;
    }

    public long addLike(String userId, String postId) {
        return txTemplate.execute(s -> doAddLike(userId, postId));
    }

    private long doAddLike(String userId, String postId) {
        FeedPost post = access.loadViewablePost(userId, postId).post();
        if (likeRepo.existsByUserIdAndPostId(userId, post.getPostId())) {
            throw ApiException.badRequest("이미 좋아요를 누른 게시물입니다.");
        }
        try {
            likeRepo.saveAndFlush(new FeedPostLike(userId, post.getPostId()));
        } catch (DataIntegrityViolationException e) {
            // 동시 클릭 race — "이미 좋아요" 와 동치로 일원화
            throw ApiException.badRequest("이미 좋아요를 누른 게시물입니다.");
        }
        long likeCount = likeRepo.countByPostId(post.getPostId());
        log.info("피드 좋아요 추가 (user_id={}, post_id={})", userId, post.getPostId());

        if (!post.getUserId().equals(userId)) {
            String recipientId = post.getUserId();
            String preview = post.getThumbnailSmallUrl();
            UserProfileView actor = userQuery.findProfile(userId).orElse(null);
            String actorName = actor != null ? actor.userName() : "";
            String actorImage = actor != null ? actor.profileImageUrl() : null;
            AfterCommit.run(() -> inboxPort.notifyFeedLike(
                    recipientId, userId, actorName, actorImage, postId, preview));
        }
        return likeCount;
    }

    @Transactional
    public long removeLike(String userId, String postId) {
        FeedPost post = access.loadViewablePost(userId, postId).post();
        if (likeRepo.deleteByUserIdAndPostId(userId, post.getPostId()) == 0) {
            throw ApiException.badRequest("좋아요를 누르지 않은 게시물입니다.");
        }
        long likeCount = likeRepo.countByPostId(post.getPostId());
        log.info("피드 좋아요 취소 (user_id={}, post_id={})", userId, post.getPostId());
        return likeCount;
    }

    @Transactional(readOnly = true)
    public LikedUsersResponse getLikedUsers(String viewerId, String postId, String cursor) {
        FeedPost post = access.loadViewablePost(viewerId, postId).post();
        Pageable page = PageRequest.of(0, PAGE_SIZE + 1);
        List<FeedPostLike> fetched = (cursor == null || cursor.isBlank())
                ? likeRepo.findLikesFirstPage(post.getPostId(), page)
                : likesAfterCursor(post.getPostId(), cursor, page);

        boolean hasMore = fetched.size() > PAGE_SIZE;
        List<FeedPostLike> likes = hasMore ? fetched.subList(0, PAGE_SIZE) : fetched;

        List<String> userIds = likes.stream().map(FeedPostLike::getUserId).toList();
        Map<String, UserProfileView> profiles = userQuery.findProfiles(userIds);
        List<LikedUserItem> items = likes.stream().map(like -> {
            UserProfileView p = profiles.get(like.getUserId());
            return new LikedUserItem(like.getUserId(),
                    p != null ? p.userName() : "",
                    p != null ? p.profileImageUrl() : null);
        }).toList();

        String nextCursor = hasMore
                ? KeysetCursor.encode(likes.get(likes.size() - 1).getCreatedAt(),
                        likes.get(likes.size() - 1).getUserId())
                : null;
        return new LikedUsersResponse(postId, items, nextCursor);
    }

    private List<FeedPostLike> likesAfterCursor(String postId, String cursor, Pageable page) {
        KeysetCursor.Decoded c = KeysetCursor.decode(cursor);
        return likeRepo.findLikesAfterCursor(postId, c.sortKey(), c.id(), page);
    }
}
