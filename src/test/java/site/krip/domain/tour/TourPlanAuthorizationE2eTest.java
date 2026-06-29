package site.krip.domain.tour;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여행 플랜 권한/유효성 경계 E2E ({@code /api/tour/plans}).
 *
 * <p>{@link TourPlanE2eTest} 가 정상 흐름과 일부 400/403/404 를 다룬다. 본 테스트는 서비스가 enforce 하지만
 * 비어 있던 경계를 메운다:
 * <ul>
 *   <li>생성 유효성: title 공백만, 카드 day_number 범위 초과</li>
 *   <li>수정 유효성: title 공백만</li>
 *   <li>권한(403): 남의 플랜 제목수정/일차추가/일차삭제/카드수정/카드삭제</li>
 *   <li>일차/카드 경계: 일차 범위 초과 삭제(400), 카드 장소 미존재(400)·카드 미존재(404)</li>
 * </ul>
 */
class TourPlanAuthorizationE2eTest extends IntegrationTestSupport {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MongoTemplate mongo;

    private String seedPlace() {
        String placeId = "place-" + UUID.randomUUID();
        Document doc = new Document()
                .append("place_id", placeId)
                .append("display_name", "테스트 장소")
                .append("address", "테스트 주소")
                .append("category", "tourist")
                .append("rating", 4.0)
                .append("photos", List.of("https://example.com/p.jpg"));
        mongo.getCollection("place").insertOne(doc);
        return placeId;
    }

    private String createPlan(String userId, String placeId) throws Exception {
        String body = """
                {
                  "title": "권한 테스트 플랜",
                  "travel_days": 2,
                  "items": [ { "day_number": 1, "place_id": "%s", "visit_time": "10:00" } ]
                }
                """.formatted(placeId);
        MvcResult res = mockMvc.perform(post("/api/tour/plans")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("plan_id").asText();
    }

    private String firstItemId(String userId, String planId) throws Exception {
        MvcResult res = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("items").get(0).get("item_id").asText();
    }

    // ──────────────────── 생성/수정 유효성 ────────────────────

    @Test
    @DisplayName("플랜 생성 — title 공백만 → 400")
    void createTitleWhitespace() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeId = seedPlace();
        String body = """
                {
                  "title": "   ",
                  "travel_days": 2,
                  "items": [ { "day_number": 1, "place_id": "%s", "visit_time": "10:00" } ]
                }
                """.formatted(placeId);
        mockMvc.perform(post("/api/tour/plans")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("플랜 생성 — 카드 day_number 가 travel_days 초과 → 400")
    void createItemDayOutOfRange() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeId = seedPlace();
        String body = """
                {
                  "title": "범위초과 생성",
                  "travel_days": 2,
                  "items": [ { "day_number": 5, "place_id": "%s", "visit_time": "10:00" } ]
                }
                """.formatted(placeId);
        mockMvc.perform(post("/api/tour/plans")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("플랜 제목 수정 — 공백만 → 400")
    void updateTitleWhitespace() throws Exception {
        String userId = fixtures.createActiveUser();
        String planId = createPlan(userId, seedPlace());

        mockMvc.perform(patch("/api/tour/plans/" + planId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────── 권한(403) ────────────────────

    @Test
    @DisplayName("남의 플랜 제목 수정 → 403")
    void updateTitleByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("플랜주인1");
        String other = fixtures.createActiveUser("플랜타인1");
        String planId = createPlan(owner, seedPlace());

        mockMvc.perform(patch("/api/tour/plans/" + planId)
                        .with(auth(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"가로채기\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("남의 플랜에 일차 추가 → 403")
    void addDayByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("플랜주인2");
        String other = fixtures.createActiveUser("플랜타인2");
        String planId = createPlan(owner, seedPlace());

        mockMvc.perform(post("/api/tour/plans/" + planId + "/days")
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("남의 플랜 일차 삭제 → 403")
    void removeDayByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("플랜주인3");
        String other = fixtures.createActiveUser("플랜타인3");
        String planId = createPlan(owner, seedPlace());

        mockMvc.perform(delete("/api/tour/plans/" + planId + "/days/1")
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("남의 플랜 카드 삭제 → 403")
    void removeItemByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("플랜주인4");
        String other = fixtures.createActiveUser("플랜타인4");
        String planId = createPlan(owner, seedPlace());
        String itemId = firstItemId(owner, planId);

        mockMvc.perform(delete("/api/tour/plans/" + planId + "/items/" + itemId)
                        .with(auth(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("남의 플랜 카드 수정 → 403")
    void updateItemByOtherUser() throws Exception {
        String owner = fixtures.createActiveUser("플랜주인5");
        String other = fixtures.createActiveUser("플랜타인5");
        String placeId = seedPlace();
        String planId = createPlan(owner, placeId);
        String itemId = firstItemId(owner, planId);

        mockMvc.perform(put("/api/tour/plans/" + planId + "/items/" + itemId)
                        .with(auth(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"place_id\": \"" + placeId + "\", \"visit_time\": \"11:00\"}"))
                .andExpect(status().isForbidden());
    }

    // ──────────────────── 일차/카드 경계 ────────────────────

    @Test
    @DisplayName("일차 범위 초과 삭제 → 400")
    void removeDayOutOfRange() throws Exception {
        String userId = fixtures.createActiveUser();
        String planId = createPlan(userId, seedPlace());

        mockMvc.perform(delete("/api/tour/plans/" + planId + "/days/99")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("카드 수정 — 존재하지 않는 장소 → 400")
    void updateItemMissingPlace() throws Exception {
        String userId = fixtures.createActiveUser();
        String planId = createPlan(userId, seedPlace());
        String itemId = firstItemId(userId, planId);

        mockMvc.perform(put("/api/tour/plans/" + planId + "/items/" + itemId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"place_id\": \"no-such-place\", \"visit_time\": \"11:00\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("카드 수정 — 존재하지 않는 카드 → 404")
    void updateMissingItem() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeId = seedPlace();
        String planId = createPlan(userId, placeId);

        mockMvc.perform(put("/api/tour/plans/" + planId + "/items/no-such-item")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"place_id\": \"" + placeId + "\", \"visit_time\": \"11:00\"}"))
                .andExpect(status().isNotFound());
    }
}
