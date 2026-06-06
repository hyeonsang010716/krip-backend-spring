package site.krip.domain.tour.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.tour.entity.TourPlanItem;

import java.util.List;

/**
 * 여행 플랜 카드 RDB 접근.
 */
public interface TourPlanItemRepository extends JpaRepository<TourPlanItem, String> {

    /** 플랜의 모든 카드 (day_number ASC, position ASC) — 이웃 position 계산용. */
    @Query("select i from TourPlanItem i where i.planId = :planId "
            + "order by i.dayNumber asc, i.position asc")
    List<TourPlanItem> findByPlanId(@Param("planId") String planId);

    /**
     * 특정 plan/day 의 카드 일괄 삭제 — 빈 day 에도 idempotent.
     *
     * <p>벌크 DELETE 라 영속성 컨텍스트와 어긋날 수 있어 clearAutomatically 로 1차 캐시를 비운다.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from TourPlanItem i where i.planId = :planId and i.dayNumber = :dayNumber")
    void deleteByPlanIdAndDayNumber(@Param("planId") String planId, @Param("dayNumber") int dayNumber);
}
