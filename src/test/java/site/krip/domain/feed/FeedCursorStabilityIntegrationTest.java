package site.krip.domain.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import site.krip.domain.feed.dto.response.CommentListResponse;
import site.krip.domain.feed.dto.response.CommentResponse;
import site.krip.domain.feed.dto.response.FeedPostListResponse;
import site.krip.domain.feed.dto.response.FeedPostResponse;
import site.krip.domain.feed.entity.FeedPost;
import site.krip.domain.feed.entity.FeedPostComment;
import site.krip.domain.feed.entity.FeedVisibility;
import site.krip.domain.feed.repository.FeedPostCommentRepository;
import site.krip.domain.feed.repository.FeedPostRow;
import site.krip.domain.feed.service.FeedPostCommentService;
import site.krip.domain.feed.service.FeedPostService;
import site.krip.global.support.KeysetCursor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 피드 게시글/댓글 커서 안정성 — 경계 행이 삭제돼도 다음 페이지가 잘리지 않는지(회귀).
 * 구버그: 커서가 id 만 담아 경계 행 삭제 시 createdAt 서브쿼리가 NULL → 빈 결과로 잘림. (createdAt, id) 인코딩으로 수정.
 */
@DisplayName("피드 커서 안정성 — 경계 행 삭제에도 페이지 무손실")
class FeedCursorStabilityIntegrationTest extends FeedTestSupport {

    @Autowired
    private FeedPostService feedPostService;
    @Autowired
    private FeedPostCommentService commentService;
    @Autowired
    private FeedPostCommentRepository commentRepository;

    @Test
    @DisplayName("피드 게시글 — 경계 글이 삭제돼도 다음 페이지가 안 잘린다")
    void feedNotTruncatedWhenBoundaryDeleted() {
        // given
        String me = fixtures.createActiveUser("나");
        seedPost(me, FeedVisibility.PUBLIC, "a");
        seedPost(me, FeedVisibility.PUBLIC, "b");

        List<FeedPostRow> rows = feedPostRepository.findByOwnerFirstPage(
                me, List.of(FeedVisibility.values()), me, PageRequest.of(0, 30));
        assertThat(rows).hasSize(2);
        FeedPost boundary = rows.get(0).post();
        FeedPost next = rows.get(1).post();
        String cursor = KeysetCursor.encode(boundary.getCreatedAt(), boundary.getPostId());

        feedPostRepository.deleteById(boundary.getPostId());
        feedPostRepository.flush();

        // when
        FeedPostListResponse page2 = feedPostService.getMyFeed(me, cursor);

        // then
        assertThat(page2.posts())
                .isNotEmpty()
                .extracting(FeedPostResponse::postId)
                .contains(next.getPostId());
    }

    @Test
    @DisplayName("피드 댓글 — 경계 댓글이 삭제돼도 다음 페이지가 안 잘린다")
    void commentsNotTruncatedWhenBoundaryDeleted() {
        // given
        String me = fixtures.createActiveUser("나");
        String postId = seedPost(me, FeedVisibility.PUBLIC, "p");
        commentRepository.saveAndFlush(new FeedPostComment(postId, me, "c1"));
        commentRepository.saveAndFlush(new FeedPostComment(postId, me, "c2"));

        List<FeedPostComment> sorted = commentRepository.findByPostFirstPage(postId, PageRequest.of(0, 20));
        assertThat(sorted).hasSize(2);
        FeedPostComment boundary = sorted.get(0);
        FeedPostComment next = sorted.get(1);
        String cursor = KeysetCursor.encode(boundary.getCreatedAt(), boundary.getCommentId());

        commentRepository.deleteById(boundary.getCommentId());
        commentRepository.flush();

        // when
        CommentListResponse page2 = commentService.listComments(me, postId, cursor);

        // then
        assertThat(page2.comments())
                .isNotEmpty()
                .extracting(CommentResponse::commentId)
                .contains(next.getCommentId());
    }
}
