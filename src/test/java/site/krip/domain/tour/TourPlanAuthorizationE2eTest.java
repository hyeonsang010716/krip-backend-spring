package site.krip.domain.tour;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여행 플랜 권한/유효성 경계 E2E ({@code /api/tour/plans}) — {@link TourPlanE2eTest} 가 다루지 않는
 * 생성/수정 유효성(공백 title·범위 초과 day), 권한 403(남의 플랜 수정/삭제), 일차·카드 경계(400/404)를 메운다.
 */
class TourPlanAuthorizationE2eTest extends TourTestSupport {

    // ──────────────────── 생성/수정 유효성 ────────────────────

    @Test
    @DisplayName("플랜 생성 — title 공백만 → 400")
    void createTitleWhitespace() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(post(PLANS)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody("   ", 2, seedPlace("장소"), 1, "10:00")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("플랜 생성 — 카드 day_number 가 travel_days 초과 → 400")
    void createItemDayOutOfRange() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(post(PLANS)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody("범위초과 생성", 2, seedPlace("장소"), 5, "10:00")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("플랜 제목 수정 — 공백만 → 400")
    void updateTitleWhitespace() throws Exception {
        String userId = fixtures.createActiveUser();
        String planId = createPlan(userId, seedPlace("장소"));

        mockMvc.perform(patch(PLANS + "/" + planId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("title", "   ")))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────── 권한(403) ────────────────────

    @Test
    @DisplayName("남의 플랜 제목 수정 → 403")
    void updateTitleByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("플랜주인1");
        String other = fixtures.createActiveUser("플랜타인1");
        String planId = createPlan(owner, seedPlace("장소"));

        mockMvc.perform(patch(PLANS + "/" + planId)
                        .with(auth(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("title", "가로채기")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("남의 플랜에 일차 추가 → 403")
    void addDayByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("플랜주인2");
        String other = fixtures.createActiveUser("플랜타인2");
        String planId = createPlan(owner, seedPlace("장소"));

        mockMvc.perform(post(PLANS + "/" + planId + "/days")
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("남의 플랜 일차 삭제 → 403")
    void removeDayByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("플랜주인3");
        String other = fixtures.createActiveUser("플랜타인3");
        String planId = createPlan(owner, seedPlace("장소"));

        mockMvc.perform(delete(PLANS + "/" + planId + "/days/1")
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("남의 플랜 카드 삭제 → 403")
    void removeItemByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("플랜주인4");
        String other = fixtures.createActiveUser("플랜타인4");
        String planId = createPlan(owner, seedPlace("장소"));
        String itemId = firstItemId(owner, planId);

        mockMvc.perform(delete(PLANS + "/" + planId + "/items/" + itemId)
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("남의 플랜 카드 수정 → 403")
    void updateItemByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("플랜주인5");
        String other = fixtures.createActiveUser("플랜타인5");
        String placeId = seedPlace("장소");
        String planId = createPlan(owner, placeId);
        String itemId = firstItemId(owner, planId);

        mockMvc.perform(put(PLANS + "/" + planId + "/items/" + itemId)
                        .with(auth(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("place_id", placeId, "visit_time", "11:00")))
                .andExpect(status().isForbidden());
    }

    // ──────────────────── 일차/카드 경계 ────────────────────

    @Test
    @DisplayName("일차 범위 초과 삭제 → 400")
    void removeDayOutOfRange() throws Exception {
        String userId = fixtures.createActiveUser();
        String planId = createPlan(userId, seedPlace("장소"));

        mockMvc.perform(delete(PLANS + "/" + planId + "/days/99")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("카드 수정 — 존재하지 않는 장소 → 400")
    void updateItemMissingPlace() throws Exception {
        String userId = fixtures.createActiveUser();
        String planId = createPlan(userId, seedPlace("장소"));
        String itemId = firstItemId(userId, planId);

        mockMvc.perform(put(PLANS + "/" + planId + "/items/" + itemId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("place_id", "no-such-place", "visit_time", "11:00")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("카드 수정 — 존재하지 않는 카드 → 404")
    void updateMissingItem() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeId = seedPlace("장소");
        String planId = createPlan(userId, placeId);

        mockMvc.perform(put(PLANS + "/" + planId + "/items/no-such-item")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("place_id", placeId, "visit_time", "11:00")))
                .andExpect(status().isNotFound());
    }
}
