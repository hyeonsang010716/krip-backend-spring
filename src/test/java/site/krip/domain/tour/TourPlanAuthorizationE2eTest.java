package site.krip.domain.tour;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;
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

    /** 플랜 컨텍스트(plan/item/place)와 헬퍼 접근을 받아 요청을 조립한다 — 파라미터화된 케이스용. */
    @FunctionalInterface
    interface PlanRequest {
        MockHttpServletRequestBuilder build(TourPlanAuthorizationE2eTest t, String planId, String itemId, String placeId);
    }

    // ──────────────────── 생성/수정 유효성 ────────────────────

    @ParameterizedTest(name = "{0} → 400")
    @MethodSource("blankTitleRequests")
    @DisplayName("title 공백만 → 400")
    void blankTitleRejected(String label, PlanRequest req) throws Exception {
        String userId = fixtures.createActiveUser();
        String placeId = seedPlace("장소");
        String planId = createPlan(userId, placeId);

        mockMvc.perform(req.build(this, planId, null, placeId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    static Stream<Arguments> blankTitleRequests() {
        return Stream.of(
                arguments("플랜 생성", (PlanRequest) (t, p, i, pl) ->
                        post(PLANS).content(t.planBody("   ", 2, pl, 1, "10:00"))),
                arguments("플랜 제목 수정", (PlanRequest) (t, p, i, pl) ->
                        patch(PLANS + "/{planId}", p).content(t.json("title", "   "))));
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

    // ──────────────────── 권한(403) ────────────────────

    @ParameterizedTest(name = "{0} → 403")
    @MethodSource("ownerOnlyRequests")
    @DisplayName("남의 플랜 변경 시도 → 403")
    void ownerOnlyForbiddenForOthers(String label, PlanRequest req) throws Exception {
        String owner = fixtures.createActiveUser("플랜주인");
        String other = fixtures.createActiveUser("플랜타인");
        String placeId = seedPlace("장소");
        String planId = createPlan(owner, placeId);
        String itemId = firstItemId(owner, planId);

        mockMvc.perform(req.build(this, planId, itemId, placeId)
                        .with(auth(other))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    static Stream<Arguments> ownerOnlyRequests() {
        return Stream.of(
                arguments("제목 수정(PATCH)", (PlanRequest) (t, p, i, pl) ->
                        patch(PLANS + "/{planId}", p).content(t.json("title", "가로채기"))),
                arguments("일차 추가(POST)", (PlanRequest) (t, p, i, pl) ->
                        post(PLANS + "/{planId}/days", p)),
                arguments("일차 삭제(DELETE)", (PlanRequest) (t, p, i, pl) ->
                        delete(PLANS + "/{planId}/days/1", p)),
                arguments("카드 삭제(DELETE)", (PlanRequest) (t, p, i, pl) ->
                        delete(PLANS + "/{planId}/items/{itemId}", p, i)),
                arguments("카드 수정(PUT)", (PlanRequest) (t, p, i, pl) ->
                        put(PLANS + "/{planId}/items/{itemId}", p, i)
                                .content(t.json("place_id", pl, "visit_time", "11:00"))));
    }

    // ──────────────────── 일차/카드 경계 ────────────────────

    @Test
    @DisplayName("일차 범위 초과 삭제 → 400")
    void removeDayOutOfRange() throws Exception {
        String userId = fixtures.createActiveUser();
        String planId = createPlan(userId, seedPlace("장소"));

        mockMvc.perform(delete(PLANS + "/{planId}/days/99", planId)
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("카드 수정 — 존재하지 않는 장소 → 400")
    void updateItemMissingPlace() throws Exception {
        String userId = fixtures.createActiveUser();
        String planId = createPlan(userId, seedPlace("장소"));
        String itemId = firstItemId(userId, planId);

        mockMvc.perform(put(PLANS + "/{planId}/items/{itemId}", planId, itemId)
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

        mockMvc.perform(put(PLANS + "/{planId}/items/no-such-item", planId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("place_id", placeId, "visit_time", "11:00")))
                .andExpect(status().isNotFound());
    }
}
