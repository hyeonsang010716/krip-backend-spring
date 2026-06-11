package site.krip.domain.feed.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.feed.entity.FeedPostLike;
import site.krip.domain.feed.entity.FeedPostLikeId;

import java.time.Instant;
import java.util.List;

/**
 * FeedPostLike RDB 접근 — 복합 PK (user_id, post_id).
 */
public interface FeedPostLikeRepository extends JpaRepository<FeedPostLike, FeedPostLikeId> {

    boolean existsByUserIdAndPostId(String userId, String postId);

    long countByPostId(String postId);

    /** owner 의 모든 게시물이 받은 좋아요 총합 (PRIVATE 포함). */
    @Query("select count(l) from FeedPostLike l, FeedPost p "
            + "where l.postId = p.postId and p.userId = :ownerId")
    long countTotalForOwner(@Param("ownerId") String ownerId);

    /** 게시물 좋아요 유저 첫 페이지 — (created_at, user_id) desc keyset 정렬. */
    @Query("select l from FeedPostLike l where l.postId = :postId "
            + "order by l.createdAt desc, l.userId desc")
    List<FeedPostLike> findLikesFirstPage(@Param("postId") String postId, Pageable pageable);

    /** 커서 이후 페이지 — 경계 행을 재조회하지 않아 동시 삭제에도 안 잘린다. */
    @Query("select l from FeedPostLike l where l.postId = :postId "
            + "and (l.createdAt < :cursorAt or (l.createdAt = :cursorAt and l.userId < :cursorUserId)) "
            + "order by l.createdAt desc, l.userId desc")
    List<FeedPostLike> findLikesAfterCursor(@Param("postId") String postId,
                                            @Param("cursorAt") Instant cursorAt,
                                            @Param("cursorUserId") String cursorUserId,
                                            Pageable pageable);

    /** 단일 bulk DELETE — 영향 row 수 반환(0 이면 미좋아요). tx 경계는 호출 서비스가 소유. */
    @Modifying(clearAutomatically = true)
    @Query("delete from FeedPostLike l where l.userId = :userId and l.postId = :postId")
    int deleteByUserIdAndPostId(@Param("userId") String userId, @Param("postId") String postId);
}
