package site.krip.domain.tour;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.support.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여행 플랜 E2E 공통 베이스 — Place 시드와 플랜/카드 생성 헬퍼를 모은다.
 * 카드 추가는 place_id 스냅샷이 필요하므로 테스트마다 MongoDB {@code place} 컬렉션에 직접 시드한다.
 */
abstract class TourTestSupport extends IntegrationTestSupport {

    protected static final String PLANS = "/api/tour/plans";

    @Autowired
    protected MongoTemplate mongo;

    /** 최소 Place 문서(GeoJSON location 포함, rating 4.5)를 시드하고 place_id 반환. */
    protected String seedPlace(String displayName) {
        return seedPlace(displayName, "서울 어딘가");
    }

    /** display_name/address 를 지정해 Place 를 시드하고 place_id 반환. */
    protected String seedPlace(String displayName, String address) {
        String placeId = "place-" + UUID.randomUUID();
        mongo.getCollection("place").insertOne(new Document()
                .append("place_id", placeId)
                .append("display_name", displayName)
                .append("address", address)
                .append("category", "tourist")
                .append("location", new Document("type", "Point")
                        .append("coordinates", List.of(126.97688, 37.57594)))
                .append("rating", 4.5)
                .append("photos", List.of("https://example.com/p1.jpg")));
        return placeId;
    }

    /** day1 카드 1개를 가진 플랜 생성 본문. */
    protected String planBody(String title, int travelDays, String placeId, int dayNumber, String visitTime) {
        return """
                { "title": "%s", "travel_days": %d,
                  "items": [ { "day_number": %d, "place_id": "%s", "visit_time": "%s" } ] }
                """.formatted(title, travelDays, dayNumber, placeId, visitTime);
    }

    /** 기본 본문으로 플랜 생성 — 플랜 내용이 무관한 테스트용. */
    protected String createPlan(String userId, String placeId) throws Exception {
        return createPlan(userId, "테스트 플랜", 2, placeId);
    }

    /** 플랜 생성(201) 후 plan_id 반환. */
    protected String createPlan(String userId, String title, int travelDays, String placeId) throws Exception {
        MvcResult res = mockMvc.perform(post(PLANS)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(title, travelDays, placeId, 1, "10:00")))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(res, "plan_id");
    }

    /** 지정 day 끝에 카드 추가(201) 후 item_id 반환. */
    protected String addItem(String planId, String userId, int day, String placeId) throws Exception {
        MvcResult res = mockMvc.perform(post(PLANS + "/" + planId + "/items")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("day_number", day, "place_id", placeId, "visit_time", "11:00")))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(res, "item_id");
    }

    /** day1 첫 카드의 item_id. */
    protected String firstItemId(String userId, String planId) throws Exception {
        MvcResult res = mockMvc.perform(get(PLANS + "/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(res).get("items").get(0).get("item_id").asText();
    }
}
