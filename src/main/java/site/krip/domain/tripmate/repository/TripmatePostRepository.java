package site.krip.domain.tripmate.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.tripmate.entity.TripmatePost;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 여행 메이트 게시글 RDB 접근.
 *
 * <p>목록/검색은 (created_at, post_id) 커서 페이지네이션. to-one(user·detail)만 fetch join
 * 하여 LIMIT 호환을 유지하고, 이미지는 {@code @BatchSize} lazy 로 로드한다.
 * like_count·is_liked 는 {@link TripmatePostLikeRepository} 로 별도 집계.
 *
 * <p>차단 관계(방향 무관)인 작성자의 글은 상관 {@code not exists} 서브쿼리로 DB 레벨에서 제외해
 * 커서 페이지 크기를 정확히 유지한다(스칼라 {@code :viewerId} 1개라 빈 컬렉션 엣지케이스가 없다).
 */
public interface TripmatePostRepository extends JpaRepository<TripmatePost, String> {

    @Query("select p from TripmatePost p "
            + "left join fetch p.user u left join fetch u.detail "
            + "where p.postId = :postId")
    Optional<TripmatePost> findByIdWithUserDetail(@Param("postId") String postId);

    @Query("select p.createdAt from TripmatePost p where p.postId = :postId")
    Optional<Instant> findCreatedAt(@Param("postId") String postId);

    /** 차단 관계(방향 무관) 작성자 제외용 상관 서브쿼리 — 모든 목록/검색 쿼리에서 공유. */
    String NOT_BLOCKED =
            "and not exists (select 1 from UserBlock b where "
            + "  (b.blockerId = :viewerId and b.blockedId = p.userId) "
            + "  or (b.blockerId = p.userId and b.blockedId = :viewerId)) ";

    // ──────────────────── 목록 (최신순) ────────────────────

    @Query("select p from TripmatePost p "
            + "left join fetch p.user u left join fetch u.detail "
            + "where p.displayed = true " + NOT_BLOCKED)
    List<TripmatePost> findDisplayedFirstPage(@Param("viewerId") String viewerId, Pageable pageable);

    @Query("select p from TripmatePost p "
            + "left join fetch p.user u left join fetch u.detail "
            + "where p.displayed = true "
            + "and (p.createdAt < :cursorAt or (p.createdAt = :cursorAt and p.postId < :cursor)) "
            + NOT_BLOCKED)
    List<TripmatePost> findDisplayedAfterCursor(@Param("cursorAt") Instant cursorAt,
                                                @Param("cursor") String cursor,
                                                @Param("viewerId") String viewerId,
                                                Pageable pageable);

    // ──────────────────── 검색 (제목·내용·작성자 닉네임) ────────────────────

    @Query("select p from TripmatePost p "
            + "left join fetch p.user u left join fetch u.detail "
            + "where p.displayed = true " + NOT_BLOCKED
            + "and ("
            + "  lower(p.title) like lower(:pattern) escape '!' "
            + "  or lower(p.content) like lower(:pattern) escape '!' "
            + "  or exists (select 1 from UserDetailInform d "
            + "             where d.userId = p.userId and lower(d.userName) like lower(:pattern) escape '!')"
            + ")")
    List<TripmatePost> searchFirstPage(@Param("pattern") String pattern,
                                       @Param("viewerId") String viewerId,
                                       Pageable pageable);

    @Query("select p from TripmatePost p "
            + "left join fetch p.user u left join fetch u.detail "
            + "where p.displayed = true "
            + "and (p.createdAt < :cursorAt or (p.createdAt = :cursorAt and p.postId < :cursor)) "
            + NOT_BLOCKED
            + "and ("
            + "  lower(p.title) like lower(:pattern) escape '!' "
            + "  or lower(p.content) like lower(:pattern) escape '!' "
            + "  or exists (select 1 from UserDetailInform d "
            + "             where d.userId = p.userId and lower(d.userName) like lower(:pattern) escape '!')"
            + ")")
    List<TripmatePost> searchAfterCursor(@Param("pattern") String pattern,
                                         @Param("cursorAt") Instant cursorAt,
                                         @Param("cursor") String cursor,
                                         @Param("viewerId") String viewerId,
                                         Pageable pageable);
}
