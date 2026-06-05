package site.krip.domain.tour.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.tour.entity.TourPlan;

import java.util.List;
import java.util.Optional;

/**
 * 여행 플랜 RDB 접근.
 */
public interface TourPlanRepository extends JpaRepository<TourPlan, String> {

    /**
     * 플랜 + 카드 목록 조회 (카드 뷰용).
     *
     * <p>{@code distinct} 로 join fetch 의 row 중복을 제거한다. 정렬은 엔티티의
     * {@code @OrderBy(dayNumber, position)} 가 보장하지만, 서비스에서 한 번 더 정렬해 의도를 명시한다.
     */
    @Query("select distinct p from TourPlan p left join fetch p.items where p.planId = :planId")
    Optional<TourPlan> findByIdWithItems(@Param("planId") String planId);

    /** 유저의 플랜 목록 (최신순, 메타만). */
    @Query("select p from TourPlan p where p.userId = :userId "
            + "order by p.updatedAt desc, p.planId desc")
    List<TourPlan> findAllByUserId(@Param("userId") String userId);
}
