package site.krip.domain.feed.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.feed.entity.FeedPost;
import site.krip.domain.feed.entity.FeedVisibility;

import java.util.Collection;
import java.util.List;

/**
 * FeedPost RDB 접근.
 *
 * <p>like_count / comment_count / viewer 좋아요 여부를 상관 서브쿼리로 단일 SELECT 합성(N+1 회피).
 * 생성자 표현식으로 {@link FeedPostRow} 에 직접 투영한다.
 * visibility 정책은 service 가 결정(visibilities IN-list 로 주입).
 */
public interface FeedPostRepository extends JpaRepository<FeedPost, String> {

    /** 그리드 3열 × 10행. */
    int PAGE_SIZE = 30;

    String SELECT = "select new site.krip.domain.feed.repository.FeedPostRow(p, "
            + "(select count(l) from FeedPostLike l where l.postId = p.postId), "
            + "(select count(c) from FeedPostComment c where c.postId = p.postId), "
            + "(case when exists (select 1 from FeedPostLike l2 "
            + "   where l2.postId = p.postId and l2.userId = :viewerId) then true else false end)) ";

    /** PK 단건 + 카운트 + viewer 좋아요 여부. */
    @Query(SELECT + "from FeedPost p where p.postId = :postId")
    List<FeedPostRow> findRowByPostId(@Param("postId") String postId, @Param("viewerId") String viewerId);

    /** owner + visibility IN-list 첫 페이지 (created_at DESC, post_id DESC). */
    @Query(SELECT + "from FeedPost p "
            + "where p.userId = :ownerId and p.visibility in :visibilities "
            + "order by p.createdAt desc, p.postId desc")
    List<FeedPostRow> findByOwnerFirstPage(@Param("ownerId") String ownerId,
                                           @Param("visibilities") Collection<FeedVisibility> visibilities,
                                           @Param("viewerId") String viewerId,
                                           Pageable pageable);

    /** owner + visibility 커서 이후 — (created_at, post_id) 튜플 비교 안정 페이지네이션. */
    @Query(SELECT + "from FeedPost p "
            + "where p.userId = :ownerId and p.visibility in :visibilities "
            + "and (p.createdAt < (select p2.createdAt from FeedPost p2 where p2.postId = :cursor) "
            + "  or (p.createdAt = (select p3.createdAt from FeedPost p3 where p3.postId = :cursor) "
            + "      and p.postId < :cursor)) "
            + "order by p.createdAt desc, p.postId desc")
    List<FeedPostRow> findByOwnerAfterCursor(@Param("ownerId") String ownerId,
                                             @Param("visibilities") Collection<FeedVisibility> visibilities,
                                             @Param("viewerId") String viewerId,
                                             @Param("cursor") String cursor,
                                             Pageable pageable);
}
