package site.krip.domain.feed.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserDetailInform;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.feed.dto.response.LikedUserItem;
import site.krip.domain.feed.dto.response.LikedUsersResponse;
import site.krip.domain.feed.entity.FeedPost;
import site.krip.domain.feed.entity.FeedPostLike;
import site.krip.domain.feed.port.FeedInboxPort;
import site.krip.domain.feed.repository.FeedPostLikeRepository;
import site.krip.global.common.exception.ApiException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 피드 좋아요 서비스.
 *
 * <p>모든 진입점이 가시성 검증 우선 — 차단 403, visibility 미충족 404. 본인 글 본인 좋아요 허용.
 * 중복 add / 미존재 remove 는 400. add 인박스 fan-out 은 트랜잭션 커밋 후 best-effort(본인→본인 skip).
 */
@Service
public class FeedPostLikeService {

    private static final Logger log = LoggerFactory.getLogger(FeedPostLikeService.class);

    private final FeedAccessService access;
    private final FeedPostLikeRepository likeRepo;
    private final UserRepository userRepo;
    private final FeedInboxPort inboxPort;
    private final TransactionTemplate txTemplate;

    public FeedPostLikeService(FeedAccessService access, FeedPostLikeRepository likeRepo,
                               UserRepository userRepo, FeedInboxPort inboxPort,
                               TransactionTemplate txTemplate) {
        this.access = access;
        this.likeRepo = likeRepo;
        this.userRepo = userRepo;
        this.inboxPort = inboxPort;
        this.txTemplate = txTemplate;
    }

    public long addLike(String userId, String postId) {
        AddLikeResult r = txTemplate.execute(s -> doAddLike(userId, postId));
        if (!r.self()) {
            inboxPort.notifyFeedLike(r.recipientId(), userId, r.actorName(),
                    r.actorProfileImageUrl(), postId, r.postPreview());
        }
        return r.likeCount();
    }

    private AddLikeResult doAddLike(String userId, String postId) {
        FeedPost post = access.loadViewablePost(userId, postId).post();
        if (likeRepo.existsByUserIdAndPostId(userId, post.getPostId())) {
            throw new ApiException(400, "이미 좋아요를 누른 게시물입니다.");
        }
        try {
            likeRepo.saveAndFlush(new FeedPostLike(userId, post.getPostId()));
        } catch (DataIntegrityViolationException e) {
            // 동시 클릭 race — "이미 좋아요" 와 동치로 일원화
            throw new ApiException(400, "이미 좋아요를 누른 게시물입니다.");
        }
        long likeCount = likeRepo.countByPostId(post.getPostId());
        log.info("피드 좋아요 추가 (user_id={}, post_id={})", userId, post.getPostId());

        if (post.getUserId().equals(userId)) {
            return new AddLikeResult(likeCount, true, post.getUserId(), "", null, null);
        }
        UserDetailInform detail = userRepo.findByIdWithProfile(userId)
                .map(User::getDetail).orElse(null);
        return new AddLikeResult(likeCount, false, post.getUserId(),
                detail != null ? detail.getUserName() : "",
                detail != null ? detail.getProfileImageUrl() : null,
                post.getThumbnailSmallUrl());
    }

    @Transactional
    public long removeLike(String userId, String postId) {
        FeedPost post = access.loadViewablePost(userId, postId).post();
        if (!likeRepo.existsByUserIdAndPostId(userId, post.getPostId())) {
            throw new ApiException(400, "좋아요를 누르지 않은 게시물입니다.");
        }
        likeRepo.deleteByUserIdAndPostId(userId, post.getPostId());
        long likeCount = likeRepo.countByPostId(post.getPostId());
        log.info("피드 좋아요 취소 (user_id={}, post_id={})", userId, post.getPostId());
        return likeCount;
    }

    @Transactional(readOnly = true)
    public LikedUsersResponse getLikedUsers(String viewerId, String postId) {
        FeedPost post = access.loadViewablePost(viewerId, postId).post();
        List<FeedPostLike> likes = likeRepo.findByPostIdOrderByCreatedAtDesc(post.getPostId());
        List<String> userIds = likes.stream().map(FeedPostLike::getUserId).toList();
        Map<String, User> userMap = userRepo.findByIdsWithProfile(userIds).stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity(), (a, b) -> a));

        List<LikedUserItem> items = likes.stream().map(like -> {
            User u = userMap.get(like.getUserId());
            UserDetailInform d = u != null ? u.getDetail() : null;
            return new LikedUserItem(like.getUserId(),
                    d != null ? d.getUserName() : "",
                    d != null ? d.getProfileImageUrl() : null);
        }).toList();
        return new LikedUsersResponse(postId, items);
    }

    private record AddLikeResult(long likeCount, boolean self, String recipientId,
                                 String actorName, String actorProfileImageUrl, String postPreview) {
    }
}
