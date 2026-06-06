package site.krip.domain.tripmate.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.tripmate.entity.TripmatePostLike;
import site.krip.domain.tripmate.entity.TripmatePostLikeId;

import java.util.List;

/**
 * 게시글 좋아요 RDB 접근.
 */
public interface TripmatePostLikeRepository extends JpaRepository<TripmatePostLike, TripmatePostLikeId> {

    long countByPostId(String postId);

    boolean existsByUserIdAndPostId(String userId, String postId);

    /** 단일 bulk DELETE — 영향 row 수 반환(0 이면 미좋아요). tx 경계는 호출 서비스가 소유. */
    @Modifying(clearAutomatically = true)
    @Query("delete from TripmatePostLike l where l.userId = :userId and l.postId = :postId")
    int deleteByUserIdAndPostId(@Param("userId") String userId, @Param("postId") String postId);

    @Query("select l.userId from TripmatePostLike l where l.postId = :postId order by l.createdAt desc")
    List<String> findUserIdsByPostId(@Param("postId") String postId);

    /** 여러 게시글의 좋아요 수 집계 (목록/검색 일괄). */
    @Query("select l.postId as postId, count(l) as cnt from TripmatePostLike l "
            + "where l.postId in :postIds group by l.postId")
    List<PostLikeCount> countByPostIds(@Param("postIds") List<String> postIds);

    /** 주어진 게시글 중 해당 유저가 좋아요한 post_id 집합. */
    @Query("select l.postId from TripmatePostLike l where l.userId = :userId and l.postId in :postIds")
    List<String> findLikedPostIds(@Param("userId") String userId, @Param("postIds") List<String> postIds);

    /** like_count 집계 프로젝션. */
    interface PostLikeCount {
        String getPostId();

        long getCnt();
    }
}
