package site.krip.domain.feed.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
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
@Slf4j
@RequiredArgsConstructor
public class FeedPostCommentService {

    private final FeedAccessService access;
    private final FeedPostCommentRepository commentRepo;
    private final UserQueryPort userQuery;
    private final FeedInboxPort inboxPort;
    private final TransactionTemplate txTemplate;

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
        // PAGE_SIZE+1 fetch — 총 개수가 PAGE_SIZE 배수일 때 빈 다음 페이지를 가리키는 phantom 커서 방지.
        PageRequest page = PageRequest.of(0, FeedPostCommentRepository.PAGE_SIZE + 1);
        List<FeedPostComment> fetched;
        if (cursor == null || cursor.isBlank()) {
            fetched = commentRepo.findByPostFirstPage(post.getPostId(), page);
        } else {
            KeysetCursor.Decoded c = KeysetCursor.decode(cursor);
            fetched = commentRepo.findByPostAfterCursor(post.getPostId(), c.sortKey(), c.id(), page);
        }
        boolean hasMore = fetched.size() > FeedPostCommentRepository.PAGE_SIZE;
        List<FeedPostComment> comments = hasMore
                ? fetched.subList(0, FeedPostCommentRepository.PAGE_SIZE) : fetched;

        List<String> userIds = comments.stream().map(FeedPostComment::getUserId).distinct().toList();
        Map<String, UserProfileView> profiles = userQuery.findProfiles(userIds);

        List<CommentResponse> items = comments.stream().map(c -> {
            UserProfileView p = profiles.get(c.getUserId());
            return CommentResponse.of(c, p != null ? p.userName() : "",
                    p != null ? p.profileImageUrl() : null);
        }).toList();

        FeedPostComment last = hasMore ? comments.get(comments.size() - 1) : null;
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
