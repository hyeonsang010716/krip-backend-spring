package site.krip.domain.tour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TourPlanItem} 스냅샷 null 가드 — Place(외부 Google 데이터)의 display_name/address 가 없을 때
 * NOT NULL 컬럼 제약 위반 대신 빈 문자열로 보정되는지 검증(회귀).
 */
@DisplayName("플랜 카드 — null display_name/address 빈 문자열 보정")
class TourPlanItemTest {

    @Test
    @DisplayName("생성자: display_name/address 가 null 이면 빈 문자열로 보정")
    void constructorCoalescesNullSnapshot() {
        TourPlanItem item = new TourPlanItem("plan-1", 1, 100.0, "place-1", null, null, "10:00");

        assertThat(item.getDisplayName()).isEmpty();
        assertThat(item.getAddress()).isEmpty();
    }

    @Test
    @DisplayName("생성자: 값이 있으면 그대로 보존")
    void constructorPreservesNonNull() {
        TourPlanItem item = new TourPlanItem("plan-1", 1, 100.0, "place-1", "경복궁", "서울 종로구", "10:00");

        assertThat(item.getDisplayName()).isEqualTo("경복궁");
        assertThat(item.getAddress()).isEqualTo("서울 종로구");
    }

    @Test
    @DisplayName("replace: display_name/address 가 null 이면 빈 문자열로 보정")
    void replaceCoalescesNullSnapshot() {
        TourPlanItem item = new TourPlanItem("plan-1", 1, 100.0, "place-1", "경복궁", "서울 종로구", "10:00");

        item.replace("place-2", null, null, "11:00");

        assertThat(item.getDisplayName()).isEmpty();
        assertThat(item.getAddress()).isEmpty();
    }
}
