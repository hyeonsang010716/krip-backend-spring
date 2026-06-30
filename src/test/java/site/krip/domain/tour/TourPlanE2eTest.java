package site.krip.domain.tour;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여행 플랜 CRUD + 카드 편집 E2E ({@code /api/tour/plans}) — 생성→조회→목록→제목수정→일차추가→
 * 카드추가/수정/이동/삭제→일차삭제→삭제 전체 흐름과 권한(403)/유효성(400)/미존재(404) 케이스.
 */
@DisplayName("여행 플랜 — 생성/조회/카드 이동·position 재정규화 전체 흐름")
class TourPlanE2eTest extends TourTestSupport {

    // ──────────────────── 전체 흐름 ────────────────────

    @Test
    @DisplayName("플랜 생성→조회→목록→제목수정→일차추가→카드추가/수정/이동/삭제→일차삭제→삭제 전체 흐름")
    void fullLifecycle() throws Exception {
        // given
        String userId = fixtures.createActiveUser("플랜작성자");
        String placeA = seedPlace("경복궁", "서울 종로구");
        String placeB = seedPlace("남산타워", "서울 용산구");
        String placeC = seedPlace("북촌한옥마을", "서울 종로구");

        // 생성 (201) — user_id 포함, day1 카드 1개
        MvcResult created = mockMvc.perform(post(PLANS)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody("서울 2박3일", 3, placeA, 1, "09:00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id").value(userId))
                .andExpect(jsonPath("$.title").value("서울 2박3일"))
                .andExpect(jsonPath("$.travel_days").value(3))
                .andExpect(jsonPath("$.plan_id").exists())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].place_id").value(placeA))
                .andExpect(jsonPath("$.items[0].day_number").value(1))
                .andExpect(jsonPath("$.items[0].display_name").value("경복궁"))
                .andExpect(jsonPath("$.items[0].visit_time").value("09:00"))
                .andReturn();
        String planId = idFrom(created, "plan_id");

        // 단건 조회 (200)
        mockMvc.perform(get(PLANS + "/{planId}", planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_id").value(planId))
                .andExpect(jsonPath("$.items.length()").value(1));

        // 목록 (200)
        mockMvc.perform(get(PLANS)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans").isArray())
                .andExpect(jsonPath("$.plans[?(@.plan_id == '" + planId + "')]").exists());

        // 제목 수정 (200, PATCH)
        mockMvc.perform(patch(PLANS + "/{planId}", planId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("title", "서울 알찬 여행")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_id").value(planId))
                .andExpect(jsonPath("$.title").value("서울 알찬 여행"));

        // 일차 추가 (201) → travel_days 4
        mockMvc.perform(post(PLANS + "/{planId}/days", planId)
                        .with(auth(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.travel_days").value(4));

        // 카드 추가 (201) — day1 끝에 placeB
        MvcResult addedItem = mockMvc.perform(post(PLANS + "/{planId}/items", planId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("day_number", 1, "place_id", placeB, "visit_time", "13:00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.place_id").value(placeB))
                .andExpect(jsonPath("$.day_number").value(1))
                .andExpect(jsonPath("$.rating").value(4.5))
                .andReturn();
        String itemBId = idFrom(addedItem, "item_id");

        // day1 두번째 카드 추가 → 이동 검증용 placeC
        MvcResult addedItemC = mockMvc.perform(post(PLANS + "/{planId}/items", planId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("day_number", 1, "place_id", placeC, "visit_time", "15:00")))
                .andExpect(status().isCreated())
                .andReturn();
        String itemCId = idFrom(addedItemC, "item_id");

        // 조회 → day1 응답 순서: placeA(첫 생성) → placeB → placeC (position 단조 증가)
        mockMvc.perform(get(PLANS + "/{planId}", planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].place_id").value(placeA))
                .andExpect(jsonPath("$.items[1].place_id").value(placeB))
                .andExpect(jsonPath("$.items[2].place_id").value(placeC));

        // 카드 수정 (PUT 200) — itemB 를 placeC 로 교체하고 visit_time 변경
        mockMvc.perform(put(PLANS + "/{planId}/items/{itemBId}", planId, itemBId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("place_id", placeC, "visit_time", "14:30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item_id").value(itemBId))
                .andExpect(jsonPath("$.place_id").value(placeC))
                .andExpect(jsonPath("$.visit_time").value("14:30"))
                .andExpect(jsonPath("$.display_name").value("북촌한옥마을"));

        // 카드 이동 (PATCH move 200) — itemC 를 day2 의 맨 앞으로 (after_item_id null)
        mockMvc.perform(patch(PLANS + "/{planId}/items/{itemCId}/move", planId, itemCId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_day_number", 2, "after_item_id", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 이동 검증 → itemC 는 day2 로, day1 엔 placeA/itemB(=placeC) 만 남음
        mockMvc.perform(get(PLANS + "/{planId}", planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.item_id == '" + itemCId + "')].day_number").value(2));

        // 카드 삭제 (200) — itemB
        mockMvc.perform(delete(PLANS + "/{planId}/items/{itemBId}", planId, itemBId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 일차 삭제 (200) — day2 (itemC 포함 비움)
        mockMvc.perform(delete(PLANS + "/{planId}/days/2", planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // day2 카드(itemC) 가 사라졌는지 → 남은 카드는 placeA 1개
        mockMvc.perform(get(PLANS + "/{planId}", planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].place_id").value(placeA));

        // 플랜 삭제 (200)
        mockMvc.perform(delete(PLANS + "/{planId}", planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 삭제 후 조회 → 404
        mockMvc.perform(get(PLANS + "/{planId}", planId)
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    // ──────────────────── 이동 / 유효성 ────────────────────

    @Test
    @DisplayName("카드 이동 — 존재하지 않는 after_item_id(bogus position) → 400")
    void moveItemBogusPosition() throws Exception {
        // given
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place A", "addr A");
        String planId = createPlan(userId, "이동 테스트", 2, placeA);

        // day1 에 카드 2개 — 자기 제외 후에도 day 가 비지 않아야 after_item_id 검증을 거친다.
        mockMvc.perform(post(PLANS + "/{planId}/items", planId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("day_number", 1, "place_id", placeA, "visit_time", "11:00")))
                .andExpect(status().isCreated());

        String itemId = firstItemId(userId, planId);

        // day1 내에 존재하지 않는 after_item_id 로 이동 → dayItems(자기 제외=1개)에서 못 찾아 computePosition 400
        mockMvc.perform(patch(PLANS + "/{planId}/items/{itemId}/move", planId, itemId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_day_number", 1, "after_item_id", "no-such-item")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("카드 이동 — after_item_id 가 자기 자신 → no-op 200 (오해성 400 아님)")
    void moveItemAfterSelfIsNoop() throws Exception {
        // given
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place A", "addr A");
        String planId = createPlan(userId, "자기이동 테스트", 2, placeA);

        String itemId = firstItemId(userId, planId);

        // X 를 X 뒤로 = 제자리 유지. 400 이 아니라 멱등 200.
        mockMvc.perform(patch(PLANS + "/{planId}/items/{itemId}/move", planId, itemId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_day_number", 1, "after_item_id", itemId)))
                .andExpect(status().isOk());

        // 위치 불변 확인 — day1 에 그대로.
        mockMvc.perform(get(PLANS + "/{planId}", planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].item_id").value(itemId))
                .andExpect(jsonPath("$.items[0].day_number").value(1));
    }

    @Test
    @DisplayName("카드 이동 — after_item_id 가 자기 자신이어도 다른 day 면 제자리 no-op 이 아니라 실제 이동")
    void moveItemAfterSelfToOtherDayMoves() throws Exception {
        // given
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place A", "addr A");
        String planId = createPlan(userId, "타day 자기이동", 2, placeA);

        String itemId = firstItemId(userId, planId);

        // day1 의 카드를 "day2, after=자기자신" 으로 이동 — 같은 day 가 아니므로 no-op 아님. day2 가 비어 정상 이동.
        mockMvc.perform(patch(PLANS + "/{planId}/items/{itemId}/move", planId, itemId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target_day_number", 2, "after_item_id", itemId)))
                .andExpect(status().isOk());

        // day2 로 실제 이동됐는지 확인 (구버그: silent no-op 으로 day1 잔존).
        mockMvc.perform(get(PLANS + "/{planId}", planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].item_id").value(itemId))
                .andExpect(jsonPath("$.items[0].day_number").value(2));
    }

    @Test
    @DisplayName("카드 이동 — 같은 슬롯 반복 삽입으로 position 갭이 붕괴해도 재정규화로 계속 성공·순서 보존")
    void moveItemRenormalizesOnGapCollapse() throws Exception {
        // given
        String userId = fixtures.createActiveUser();
        String planId = createPlan(userId, "갭 붕괴", 1, seedPlace("c0", "addr0"));

        String c0 = firstItemId(userId, planId);
        String c1 = addItem(planId, userId, 1, seedPlace("c1", "addr1"));
        String c2 = addItem(planId, userId, 1, seedPlace("c2", "addr2"));

        // c1/c2 를 번갈아 "c0 바로 뒤"로 이동 — 갭이 절반씩 붕괴해도 day 재정규화 폴백으로 계속 200.
        for (int i = 0; i < 60; i++) {
            String moving = (i % 2 == 0) ? c2 : c1;
            mockMvc.perform(patch(PLANS + "/{planId}/items/{moving}/move", planId, moving)
                            .with(auth(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("target_day_number", 1, "after_item_id", c0)))
                    .andExpect(status().isOk());
        }

        // 최종: 3장 보존, 마지막 이동(i=59→c1)이 c0 바로 뒤 → 순서 [c0, c1, c2], 모두 day1.
        mockMvc.perform(get(PLANS + "/{planId}", planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].item_id").value(c0))
                .andExpect(jsonPath("$.items[1].item_id").value(c1))
                .andExpect(jsonPath("$.items[2].item_id").value(c2))
                .andExpect(jsonPath("$.items[2].day_number").value(1));
    }

    @Test
    @DisplayName("카드 추가 — day_number 가 travel_days 범위 초과 → 400")
    void addItemDayOutOfRange() throws Exception {
        // given
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place B", "addr B");
        String planId = createPlan(userId, "범위 테스트", 2, placeA);

        // when & then
        mockMvc.perform(post(PLANS + "/{planId}/items", planId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("day_number", 9, "place_id", placeA)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("카드 추가 — 존재하지 않는 place_id → 400")
    void addItemMissingPlace() throws Exception {
        // given
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place C", "addr C");
        String planId = createPlan(userId, "장소 누락", 2, placeA);

        // when & then
        mockMvc.perform(post(PLANS + "/{planId}/items", planId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("day_number", 1, "place_id", "no-such-place")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("플랜 생성 — items 비어있음 → 400")
    void createPlanEmptyItems() throws Exception {
        // given
        String userId = fixtures.createActiveUser();

        // when & then
        mockMvc.perform(post(PLANS)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("title", "빈 플랜", "travel_days", 2, "items", List.of())))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────── 권한 / 미존재 ────────────────────

    @Test
    @DisplayName("다른 유저가 남의 플랜 조회 → 403")
    void getPlanByOtherUserForbidden() throws Exception {
        // given
        String owner = fixtures.createActiveUser("소유자");
        String other = fixtures.createActiveUser("타인");
        String placeA = seedPlace("place D", "addr D");
        String planId = createPlan(owner, "비공개 플랜", 2, placeA);

        // when & then
        mockMvc.perform(get(PLANS + "/{planId}", planId)
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("다른 유저가 남의 플랜 삭제 → 403")
    void deletePlanByOtherUserForbidden() throws Exception {
        // given
        String owner = fixtures.createActiveUser("소유자2");
        String other = fixtures.createActiveUser("타인2");
        String placeA = seedPlace("place E", "addr E");
        String planId = createPlan(owner, "삭제 권한", 2, placeA);

        // when & then
        mockMvc.perform(delete(PLANS + "/{planId}", planId)
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("존재하지 않는 플랜 조회 → 404")
    void getMissingPlan() throws Exception {
        // given
        String userId = fixtures.createActiveUser();

        // when & then
        mockMvc.perform(get(PLANS + "/no-such-plan")
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 카드 삭제 → 404")
    void removeMissingItem() throws Exception {
        // given
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place F", "addr F");
        String planId = createPlan(userId, "카드 미존재", 2, placeA);

        // when & then
        mockMvc.perform(delete(PLANS + "/{planId}/items/no-such-item", planId)
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("인증 없이 플랜 목록 → 401")
    void listUnauthenticated() throws Exception {
        mockMvc.perform(get(PLANS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("생성 — travel_days > 365 → 400 (비현실적 거대 플랜 차단)")
    void travelDaysOverMax() throws Exception {
        // given
        String userId = fixtures.createActiveUser("거대플랜");

        // @Max(365) 가 place 조회 이전에 거부하므로 더미 place_id 로 충분.
        mockMvc.perform(post(PLANS)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody("초장기", 400, "place-dummy", 1, "10:00")))
                .andExpect(status().isBadRequest());
    }
}
