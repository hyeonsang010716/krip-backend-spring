package site.krip.domain.feed.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.port.UserProfileView;
import site.krip.domain.auth.port.UserQueryPort;
import site.krip.domain.feed.dto.response.CommentListResponse;
import site.krip.domain.feed.dto.response.CommentResponse;
import site.krip.domain.feed.entity.FeedPost;
import site.krip.domain.feed.entity.FeedPostComment;
import site.krip.domain.feed.exception.FeedPostCommentNotFoundException;
import site.krip.domain.feed.port.FeedInboxPort;
import site.krip.domain.feed.repository.FeedPostCommentRepository;
import site.krip.global.common.exception.ApiException;
import site.krip.global.support.AfterCommit;
import site.krip.global.support.KeysetCursor;

import java.util.List;
import java.util.Map;

/**
 * 피드 댓글 서비스.
 *
 * <p>게시물을 볼 수 있는 viewer 만 작성/조회. 삭제는 작성자 본인만(글 owner 라도 불가). 빈 본문 400.
 * create 인박스 fan-out 은 트랜잭션 커밋 후 best-effort(본인→본인 skip). delete 는 cascade 안 함(보존).
 */
@Service
public class FeedPostCommentService {

    private static final Logger log = LoggerFactory.getLogger(FeedPostCommentService.class);

    private final FeedAccessService access;
    private final FeedPostCommentRepository commentRepo;
    private final UserQueryPort userQuery;
    private final FeedInboxPort inboxPort;
    private final TransactionTemplate txTemplate;

    public FeedPostCommentService(FeedAccessService access, FeedPostCommentRepository commentRepo,
                                  UserQueryPort userQuery, FeedInboxPort inboxPort,
                                  TransactionTemplate txTemplate) {
        this.access = access;
        this.commentRepo = commentRepo;
        this.userQuery = userQuery;
        this.inboxPort = inboxPort;
        this.txTemplate = txTemplate;
    }

    public CommentResponse createComment(String userId, String postId, String content) {
        return txTemplate.execute(s -> doCreateComment(userId, postId, content));
    }

    private CommentResponse doCreateComment(String userId, String postId, String content) {
        FeedPost post = access.loadViewablePost(userId, postId).post();
        String normalized = normalizeContent(content);

        FeedPostComment saved = commentRepo.saveAndFlush(
                new FeedPostComment(post.getPostId(), userId, normalized));
        log.info("피드 댓글 작성 (user_id={}, post_id={}, comment_id={})",
                userId, post.getPostId(), saved.getCommentId());

        UserProfileView actor = userQuery.findProfile(userId).orElse(null);
        CommentResponse dto = CommentResponse.of(saved,
                actor != null ? actor.userName() : "",
                actor != null ? actor.profileImageUrl() : null);

        if (!post.getUserId().equals(userId)) {
            String recipientId = post.getUserId();
            String preview = post.getThumbnailSmallUrl();
            AfterCommit.run(() -> inboxPort.notifyFeedComment(recipientId, userId, dto.userName(),
                    dto.profileImageUrl(), postId, preview, dto.commentId(), dto.content()));
        }
        return dto;
    }

    @Transactional(readOnly = true)
    public CommentListResponse listComments(String viewerId, String postId, String cursor) {
        FeedPost post = access.loadViewablePost(viewerId, postId).post();
        PageRequest page = PageRequest.of(0, FeedPostCommentRepository.PAGE_SIZE);
        List<FeedPostComment> comments;
        if (cursor == null) {
            comments = commentRepo.findByPostFirstPage(post.getPostId(), page);
        } else {
            KeysetCursor.Decoded c = KeysetCursor.decode(cursor);
            comments = commentRepo.findByPostAfterCursor(post.getPostId(), c.sortKey(), c.id(), page);
        }

        List<String> userIds = comments.stream().map(FeedPostComment::getUserId).distinct().toList();
        Map<String, UserProfileView> profiles = userQuery.findProfiles(userIds);

        List<CommentResponse> items = comments.stream().map(c -> {
            UserProfileView p = profiles.get(c.getUserId());
            return CommentResponse.of(c, p != null ? p.userName() : "",
                    p != null ? p.profileImageUrl() : null);
        }).toList();

        FeedPostComment last = comments.size() == FeedPostCommentRepository.PAGE_SIZE
                ? comments.get(comments.size() - 1) : null;
        String nextCursor = last == null ? null : KeysetCursor.encode(last.getCreatedAt(), last.getCommentId());
        return new CommentListResponse(items, nextCursor);
    }

    @Transactional
    public void deleteComment(String userId, String postId, String commentId) {
        FeedPostComment comment = commentRepo.findById(commentId).orElse(null);
        if (comment == null || !comment.getPostId().equals(postId)) {
            throw new FeedPostCommentNotFoundException("존재하지 않는 댓글입니다.");
        }
        if (!comment.getUserId().equals(userId)) {
            throw ApiException.forbidden("댓글에 대한 권한이 없습니다.");
        }
        commentRepo.delete(comment);
        log.info("피드 댓글 삭제 (user_id={}, post_id={}, comment_id={})", userId, postId, commentId);
    }

    /** strip 후 빈이면 400 (공백만 입력 차단). */
    private static String normalizeContent(String content) {
        String stripped = content.strip();
        if (stripped.isEmpty()) {
            throw ApiException.badRequest("댓글 내용이 비어 있습니다.");
        }
        return stripped;
    }
}
