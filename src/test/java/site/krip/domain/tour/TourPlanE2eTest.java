package site.krip.domain.tour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.support.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여행 플랜 CRUD + 카드 편집 E2E — 생성→조회→목록→제목수정→일차추가→카드추가/수정/이동/삭제→일차삭제→삭제 의
 * 전체 흐름과 권한(403)/유효성(400)/미존재(404) 케이스 검증.
 *
 * <p>경로: {@code /api/tour/plans}. 카드 추가는 place_id 스냅샷이 필요하므로
 * 테스트 시작 시 최소 Place 문서를 MongoDB {@code place} 컬렉션에 BSON 으로 직접 시드한다.
 */
class TourPlanE2eTest extends IntegrationTestSupport {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MongoTemplate mongo;

    // ──────────────────── 시드 / 헬퍼 ────────────────────

    /** 최소 Place 문서를 place 컬렉션에 시드하고 place_id 반환. display_name/address/rating/photos 만 채운다. */
    private String seedPlace(String displayName, String address) {
        String placeId = "place-" + UUID.randomUUID();
        Document doc = new Document()
                .append("place_id", placeId)
                .append("display_name", displayName)
                .append("address", address)
                .append("category", "tourist")
                .append("rating", 4.5)
                .append("photos", List.of("https://example.com/p1.jpg"));
        mongo.getCollection("place").insertOne(doc);
        return placeId;
    }

    private String createPlanBody(String title, int travelDays, String placeId, int dayNumber, String visitTime) {
        return """
                {
                  "title": "%s",
                  "travel_days": %d,
                  "items": [
                    { "day_number": %d, "place_id": "%s", "visit_time": "%s" }
                  ]
                }
                """.formatted(title, travelDays, dayNumber, placeId, visitTime);
    }

    /** 플랜 생성 후 plan_id 반환 (선행 데이터 준비용). */
    private String createPlan(String userId, String title, int travelDays, String placeId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/tour/plans")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlanBody(title, travelDays, placeId, 1, "10:00")))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("plan_id").asText();
    }

    // ──────────────────── 전체 흐름 ────────────────────

    @Test
    @DisplayName("플랜 생성→조회→목록→제목수정→일차추가→카드추가/수정/이동/삭제→일차삭제→삭제 전체 흐름")
    void fullLifecycle() throws Exception {
        String userId = fixtures.createActiveUser("플랜작성자");
        String placeA = seedPlace("경복궁", "서울 종로구");
        String placeB = seedPlace("남산타워", "서울 용산구");
        String placeC = seedPlace("북촌한옥마을", "서울 종로구");

        // 생성 (201) — user_id 포함, day1 카드 1개
        MvcResult created = mockMvc.perform(post("/api/tour/plans")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlanBody("서울 2박3일", 3, placeA, 1, "09:00")))
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
        String planId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("plan_id").asText();

        // 단건 조회 (200)
        mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_id").value(planId))
                .andExpect(jsonPath("$.items.length()").value(1));

        // 목록 (200)
        mockMvc.perform(get("/api/tour/plans")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans").isArray())
                .andExpect(jsonPath("$.plans[?(@.plan_id == '" + planId + "')]").exists());

        // 제목 수정 (200, PATCH)
        mockMvc.perform(patch("/api/tour/plans/" + planId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"서울 알찬 여행\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_id").value(planId))
                .andExpect(jsonPath("$.title").value("서울 알찬 여행"));

        // 일차 추가 (201) → travel_days 4
        mockMvc.perform(post("/api/tour/plans/" + planId + "/days")
                        .with(auth(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.travel_days").value(4));

        // 카드 추가 (201) — day1 끝에 placeB
        MvcResult addedItem = mockMvc.perform(post("/api/tour/plans/" + planId + "/items")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day_number\": 1, \"place_id\": \"" + placeB + "\", \"visit_time\": \"13:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.place_id").value(placeB))
                .andExpect(jsonPath("$.day_number").value(1))
                .andExpect(jsonPath("$.rating").value(4.5))
                .andReturn();
        String itemBId = objectMapper.readTree(addedItem.getResponse().getContentAsString())
                .get("item_id").asText();

        // day1 두번째 카드 추가 → 이동 검증용 placeC
        MvcResult addedItemC = mockMvc.perform(post("/api/tour/plans/" + planId + "/items")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day_number\": 1, \"place_id\": \"" + placeC + "\", \"visit_time\": \"15:00\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String itemCId = objectMapper.readTree(addedItemC.getResponse().getContentAsString())
                .get("item_id").asText();

        // 조회 → day1 응답 순서: placeA(첫 생성) → placeB → placeC (position 단조 증가)
        mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].place_id").value(placeA))
                .andExpect(jsonPath("$.items[1].place_id").value(placeB))
                .andExpect(jsonPath("$.items[2].place_id").value(placeC));

        // 카드 수정 (PUT 200) — itemB 를 placeC 로 교체하고 visit_time 변경
        mockMvc.perform(put("/api/tour/plans/" + planId + "/items/" + itemBId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"place_id\": \"" + placeC + "\", \"visit_time\": \"14:30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item_id").value(itemBId))
                .andExpect(jsonPath("$.place_id").value(placeC))
                .andExpect(jsonPath("$.visit_time").value("14:30"))
                .andExpect(jsonPath("$.display_name").value("북촌한옥마을"));

        // 카드 이동 (PATCH move 200) — itemC 를 day2 의 맨 앞으로 (after_item_id null)
        mockMvc.perform(patch("/api/tour/plans/" + planId + "/items/" + itemCId + "/move")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_day_number\": 2, \"after_item_id\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 이동 검증 → itemC 는 day2 로, day1 엔 placeA/itemB(=placeC) 만 남음
        mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.item_id == '" + itemCId + "')].day_number").value(2));

        // 카드 삭제 (200) — itemB
        mockMvc.perform(delete("/api/tour/plans/" + planId + "/items/" + itemBId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 일차 삭제 (200) — day2 (itemC 포함 비움)
        mockMvc.perform(delete("/api/tour/plans/" + planId + "/days/2")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // day2 카드(itemC) 가 사라졌는지 → 남은 카드는 placeA 1개
        mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].place_id").value(placeA));

        // 플랜 삭제 (200)
        mockMvc.perform(delete("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 삭제 후 조회 → 404
        mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    // ──────────────────── 이동 / 유효성 ────────────────────

    @Test
    @DisplayName("카드 이동 — 존재하지 않는 after_item_id(bogus position) → 400")
    void moveItemBogusPosition() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place A", "addr A");
        String planId = createPlan(userId, "이동 테스트", 2, placeA);

        // day1 에 카드를 하나 더 추가 → day1 에 카드 2개. (이동 대상 자기 자신을 제외해도 dayItems 가 비지 않아야
        //  after_item_id 검증을 거친다. 카드가 1개뿐이면 자기 제외 후 빈 day → SPACING 반환=정상 200, 검증 미발생.)
        mockMvc.perform(post("/api/tour/plans/" + planId + "/items")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day_number\": 1, \"place_id\": \"" + placeA + "\", \"visit_time\": \"11:00\"}"))
                .andExpect(status().isCreated());

        // day1 의 첫 카드 id 조회
        MvcResult plan = mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andReturn();
        String itemId = objectMapper.readTree(plan.getResponse().getContentAsString())
                .get("items").get(0).get("item_id").asText();

        // day1 내에 존재하지 않는 after_item_id 로 이동 → dayItems(자기 제외=1개)에서 못 찾아 computePosition 400
        mockMvc.perform(patch("/api/tour/plans/" + planId + "/items/" + itemId + "/move")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_day_number\": 1, \"after_item_id\": \"no-such-item\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("카드 이동 — after_item_id 가 자기 자신 → no-op 200 (오해성 400 아님)")
    void moveItemAfterSelfIsNoop() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place A", "addr A");
        String planId = createPlan(userId, "자기이동 테스트", 2, placeA);

        String itemId = objectMapper.readTree(mockMvc.perform(get("/api/tour/plans/" + planId)
                                .with(auth(userId)))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("items").get(0).get("item_id").asText();

        // X 를 X 뒤로 = 제자리 유지. 400 이 아니라 멱등 200.
        mockMvc.perform(patch("/api/tour/plans/" + planId + "/items/" + itemId + "/move")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_day_number\": 1, \"after_item_id\": \"" + itemId + "\"}"))
                .andExpect(status().isOk());

        // 위치 불변 확인 — day1 에 그대로.
        mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].item_id").value(itemId))
                .andExpect(jsonPath("$.items[0].day_number").value(1));
    }

    @Test
    @DisplayName("카드 이동 — after_item_id 가 자기 자신이어도 다른 day 면 제자리 no-op 이 아니라 실제 이동")
    void moveItemAfterSelfToOtherDayMoves() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place A", "addr A");
        String planId = createPlan(userId, "타day 자기이동", 2, placeA);

        String itemId = objectMapper.readTree(mockMvc.perform(get("/api/tour/plans/" + planId)
                                .with(auth(userId)))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("items").get(0).get("item_id").asText();

        // day1 의 카드를 "day2, after=자기자신" 으로 이동 — 같은 day 가 아니므로 no-op 아님. day2 가 비어 정상 이동.
        mockMvc.perform(patch("/api/tour/plans/" + planId + "/items/" + itemId + "/move")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_day_number\": 2, \"after_item_id\": \"" + itemId + "\"}"))
                .andExpect(status().isOk());

        // day2 로 실제 이동됐는지 확인 (구버그: silent no-op 으로 day1 잔존).
        mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].item_id").value(itemId))
                .andExpect(jsonPath("$.items[0].day_number").value(2));
    }

    @Test
    @DisplayName("카드 이동 — 같은 슬롯 반복 삽입으로 position 갭이 붕괴해도 재정규화로 계속 성공·순서 보존")
    void moveItemRenormalizesOnGapCollapse() throws Exception {
        String userId = fixtures.createActiveUser();
        String planId = createPlan(userId, "갭 붕괴", 1, seedPlace("c0", "addr0"));

        String c0 = objectMapper.readTree(mockMvc.perform(get("/api/tour/plans/" + planId)
                                .with(auth(userId)))
                        .andReturn().getResponse().getContentAsString())
                .get("items").get(0).get("item_id").asText();
        String c1 = addCard(planId, userId, seedPlace("c1", "addr1"));
        String c2 = addCard(planId, userId, seedPlace("c2", "addr2"));

        // c1/c2 를 번갈아 "c0 바로 뒤"로 이동 — 매번 c0 와 후속 사이 갭이 절반으로 줄어 ~51회째 double 표현 한계.
        // 붕괴 후 retry 가 같은 충돌값만 재계산하면 400 이지만, day 재정규화 폴백이 끼어 계속 200 이어야 한다.
        for (int i = 0; i < 60; i++) {
            String moving = (i % 2 == 0) ? c2 : c1;
            mockMvc.perform(patch("/api/tour/plans/" + planId + "/items/" + moving + "/move")
                            .with(auth(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"target_day_number\": 1, \"after_item_id\": \"" + c0 + "\"}"))
                    .andExpect(status().isOk());
        }

        // 최종: 3장 보존, 마지막 이동(i=59→c1)이 c0 바로 뒤 → 순서 [c0, c1, c2], 모두 day1.
        mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].item_id").value(c0))
                .andExpect(jsonPath("$.items[1].item_id").value(c1))
                .andExpect(jsonPath("$.items[2].item_id").value(c2))
                .andExpect(jsonPath("$.items[2].day_number").value(1));
    }

    /** day1 끝에 카드 추가 후 item_id 반환. */
    private String addCard(String planId, String userId, String placeId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/tour/plans/" + planId + "/items")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day_number\": 1, \"place_id\": \"" + placeId + "\", \"visit_time\": \"10:00\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("item_id").asText();
    }

    @Test
    @DisplayName("카드 추가 — day_number 가 travel_days 범위 초과 → 400")
    void addItemDayOutOfRange() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place B", "addr B");
        String planId = createPlan(userId, "범위 테스트", 2, placeA);

        mockMvc.perform(post("/api/tour/plans/" + planId + "/items")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day_number\": 9, \"place_id\": \"" + placeA + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("카드 추가 — 존재하지 않는 place_id → 400")
    void addItemMissingPlace() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place C", "addr C");
        String planId = createPlan(userId, "장소 누락", 2, placeA);

        mockMvc.perform(post("/api/tour/plans/" + planId + "/items")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day_number\": 1, \"place_id\": \"no-such-place\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("플랜 생성 — items 비어있음 → 400")
    void createPlanEmptyItems() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(post("/api/tour/plans")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"빈 플랜\", \"travel_days\": 2, \"items\": []}"))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────── 권한 / 미존재 ────────────────────

    @Test
    @DisplayName("다른 유저가 남의 플랜 조회 → 403")
    void getPlanByOtherUserForbidden() throws Exception {
        String owner = fixtures.createActiveUser("소유자");
        String other = fixtures.createActiveUser("타인");
        String placeA = seedPlace("place D", "addr D");
        String planId = createPlan(owner, "비공개 플랜", 2, placeA);

        mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("다른 유저가 남의 플랜 삭제 → 403")
    void deletePlanByOtherUserForbidden() throws Exception {
        String owner = fixtures.createActiveUser("소유자2");
        String other = fixtures.createActiveUser("타인2");
        String placeA = seedPlace("place E", "addr E");
        String planId = createPlan(owner, "삭제 권한", 2, placeA);

        mockMvc.perform(delete("/api/tour/plans/" + planId)
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("존재하지 않는 플랜 조회 → 404")
    void getMissingPlan() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(get("/api/tour/plans/no-such-plan")
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 카드 삭제 → 404")
    void removeMissingItem() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeA = seedPlace("place F", "addr F");
        String planId = createPlan(userId, "카드 미존재", 2, placeA);

        mockMvc.perform(delete("/api/tour/plans/" + planId + "/items/no-such-item")
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("인증 없이 플랜 목록 → 401")
    void listUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/tour/plans"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("생성 — travel_days > 365 → 400 (비현실적 거대 플랜 차단)")
    void travelDaysOverMax() throws Exception {
        String userId = fixtures.createActiveUser("거대플랜");

        // @Max(365) 가 place 조회 이전에 거부하므로 더미 place_id 로 충분.
        mockMvc.perform(post("/api/tour/plans")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPlanBody("초장기", 400, "place-dummy", 1, "10:00")))
                .andExpect(status().isBadRequest());
    }
}
