package site.krip.domain.feed.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.feed.entity.FeedPostComment;

import java.util.List;

/**
 * FeedPostComment RDB 접근. 커서: (created_at DESC, comment_id DESC).
 */
public interface FeedPostCommentRepository extends JpaRepository<FeedPostComment, String> {

    /** 모바일 한 화면 fit. */
    int PAGE_SIZE = 20;

    @Query("select c from FeedPostComment c where c.postId = :postId "
            + "order by c.createdAt desc, c.commentId desc")
    List<FeedPostComment> findByPostFirstPage(@Param("postId") String postId, Pageable pageable);

    @Query("select c from FeedPostComment c where c.postId = :postId "
            + "and (c.createdAt < (select c2.createdAt from FeedPostComment c2 where c2.commentId = :cursor) "
            + "  or (c.createdAt = (select c3.createdAt from FeedPostComment c3 where c3.commentId = :cursor) "
            + "      and c.commentId < :cursor)) "
            + "order by c.createdAt desc, c.commentId desc")
    List<FeedPostComment> findByPostAfterCursor(@Param("postId") String postId,
                                                @Param("cursor") String cursor, Pageable pageable);
}
