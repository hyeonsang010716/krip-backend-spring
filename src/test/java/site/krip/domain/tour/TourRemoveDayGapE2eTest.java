package site.krip.domain.tour;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * remove_day 의 gap 보존 + day_number monotonic 검증 — day 를 비워도 travel_days 는 유지하고(gap 보존),
 * 이후 add_day 는 gap 재사용 없이 max+1 을 부여하는지 본다.
 */
@DisplayName("여행 플랜 일차 삭제 — travel_days 불변·gap 보존")
class TourRemoveDayGapE2eTest extends TourTestSupport {

    @Test
    @DisplayName("remove_day → travel_days 불변(gap 보존), 다른 day 항목 유지, 이후 add_day 는 max+1")
    void removeDayPreservesGapThenAddDayIsMaxPlusOne() throws Exception {
        // given
        String user = fixtures.createActiveUser("plan유저");
        String placeId = seedPlace("장소");

        // travel_days=3, day1 에 1개 항목으로 생성
        String planId = createPlan(user, "3일 플랜", 3, placeId);

        addItem(planId, user, 2, placeId);
        addItem(planId, user, 3, placeId);

        // day2 삭제
        mockMvc.perform(delete(PLANS + "/{planId}/days/{day}", planId, 2)
                        .with(auth(user)))
                .andExpect(status().isOk());

        // travel_days 불변(3), day2 항목만 사라지고 day1/day3 유지(gap 보존)
        mockMvc.perform(get(PLANS + "/{planId}", planId)
                        .with(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.travel_days").value(3))
                .andExpect(jsonPath("$.items[?(@.day_number==2)]", hasSize(0)))
                .andExpect(jsonPath("$.items[?(@.day_number==1)]", hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.day_number==3)]", hasSize(1)));

        // add_day → travel_days = max+1 = 4 (gap 재사용 안 함)
        mockMvc.perform(post(PLANS + "/{planId}/days", planId)
                        .with(auth(user)))
                .andExpect(status().isCreated());

        mockMvc.perform(get(PLANS + "/{planId}", planId)
                        .with(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.travel_days").value(4));
    }
}
