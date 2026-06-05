package site.krip.domain.tripmate.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.tripmate.entity.TripmatePostImage;

import java.util.List;

/**
 * 게시글 첨부 이미지 RDB 접근.
 */
public interface TripmatePostImageRepository extends JpaRepository<TripmatePostImage, String> {

    List<TripmatePostImage> findByPostIdOrderByImageOrderAsc(String postId);

    /** 유저의 게시글에 연결된 이미지 URL 전체 (고아 이미지 정리용). */
    @Query("select i.imageUrl from TripmatePostImage i, TripmatePost p "
            + "where i.postId = p.postId and p.userId = :userId")
    List<String> findUrlsByUserId(@Param("userId") String userId);

    @Modifying
    @Query("delete from TripmatePostImage i where i.postId = :postId")
    void deleteByPostId(@Param("postId") String postId);
}
