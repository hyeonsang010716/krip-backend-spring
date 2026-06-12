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

    /** 대상 day 의 최대 position (빈 day 면 null) — addItem 의 맨 끝 append 용. */
    @Query("select max(i.position) from TourPlanItem i "
            + "where i.planId = :planId and i.dayNumber = :dayNumber")
    Double findMaxPosition(@Param("planId") String planId, @Param("dayNumber") int dayNumber);

    /** 대상 day 의 카드 (position ASC) — moveItem 의 분수 position 계산용. */
    @Query("select i from TourPlanItem i where i.planId = :planId and i.dayNumber = :dayNumber "
            + "order by i.position asc")
    List<TourPlanItem> findByPlanIdAndDayNumber(@Param("planId") String planId,
                                                @Param("dayNumber") int dayNumber);

    /**
     * 특정 plan/day 의 카드 일괄 삭제 — 빈 day 에도 idempotent.
     *
     * <p>벌크 DELETE 라 영속성 컨텍스트와 어긋날 수 있어 clearAutomatically 로 1차 캐시를 비운다.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from TourPlanItem i where i.planId = :planId and i.dayNumber = :dayNumber")
    void deleteByPlanIdAndDayNumber(@Param("planId") String planId, @Param("dayNumber") int dayNumber);
}
