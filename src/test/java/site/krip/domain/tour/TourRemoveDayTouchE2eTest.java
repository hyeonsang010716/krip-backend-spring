package site.krip.domain.tour;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * remove_day 가 plan 의 {@code updated_at} 을 갱신하는지 검증(회귀).
 *
 * <p>벌크 삭제({@code @Modifying(clearAutomatically=true)})가 영속성 컨텍스트를 비워 plan 을 detach 시키면
 * 이후 {@code plan.touch()} 가 무효화돼 갱신이 유실됐었다. 목록은 {@code updated_at desc} 정렬이므로
 * "먼저 만든 플랜의 day 를 삭제하면 목록 맨 앞으로 올라온다"로 갱신 여부를 행위로 검증한다.
 */
@DisplayName("여행 플랜 일차 삭제 — updated_at 갱신(목록 정렬 반영)")
class TourRemoveDayTouchE2eTest extends TourTestSupport {

    @Test
    @DisplayName("remove_day → plan.updated_at 갱신(목록 최신순에서 해당 플랜이 맨 앞으로 이동)")
    void removeDayBumpsUpdatedAt() throws Exception {
        String user = fixtures.createActiveUser("plan유저");
        String placeId = seedPlace("장소");

        // 먼저 A, 그다음 B 생성 → 초기 최신순은 [B, A]
        String planA = createPlan(user, "플랜 A", 2, placeId);
        String planB = createPlan(user, "플랜 B", 2, placeId);

        mockMvc.perform(get(PLANS)
                        .with(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[0].plan_id").value(planB))
                .andExpect(jsonPath("$.plans[1].plan_id").value(planA));

        // A 의 day 1 삭제 → A.updated_at 이 B 생성 시점보다 뒤로 갱신돼야 함
        mockMvc.perform(delete(PLANS + "/{planA}/days/{day}", planA, 1)
                        .with(auth(user)))
                .andExpect(status().isOk());

        // 갱신됐다면 최신순은 [A, B] 로 뒤집힌다
        mockMvc.perform(get(PLANS)
                        .with(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[0].plan_id").value(planA))
                .andExpect(jsonPath("$.plans[1].plan_id").value(planB));
    }
}
