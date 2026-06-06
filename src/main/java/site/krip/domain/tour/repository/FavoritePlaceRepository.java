package site.krip.domain.tour.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.tour.entity.FavoritePlace;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 즐겨찾기 RDB 접근.
 */
public interface FavoritePlaceRepository extends JpaRepository<FavoritePlace, String> {

    Optional<FavoritePlace> findByUserIdAndPlaceId(String userId, String placeId);

    boolean existsByUserIdAndPlaceId(String userId, String placeId);

    /** 단일 bulk DELETE — 영향 row 수 반환(0 이면 미즐겨찾기). tx 경계는 호출 서비스가 소유. */
    @Modifying(clearAutomatically = true)
    @Query("delete from FavoritePlace f where f.userId = :userId and f.placeId = :placeId")
    int deleteByUserIdAndPlaceId(@Param("userId") String userId, @Param("placeId") String placeId);

    /** 유저의 즐겨찾기 목록 (최신순). */
    @Query("select f from FavoritePlace f where f.userId = :userId order by f.createdAt desc")
    List<FavoritePlace> findAllByUserOrderByCreatedAtDesc(@Param("userId") String userId);

    /** 주어진 place_id 중 유저가 즐겨찾기한 것만 (목록 화면 is_favorite 배치 표시용). */
    @Query("select f.placeId from FavoritePlace f "
            + "where f.userId = :userId and f.placeId in :placeIds")
    List<String> findFavoritedPlaceIds(@Param("userId") String userId,
                                       @Param("placeIds") Collection<String> placeIds);
}
