package site.krip.domain.tour;

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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여행 플랜 remove_day gap 보존 + day_number monotonic E2E.
 * day 항목을 비워도 travel_days 는 그대로 두고(gap 보존), 이후 add_day 는 max+1 을 부여하는지 검증한다.
 */
class TourRemoveDayGapE2eTest extends IntegrationTestSupport {

    @Autowired
    private MongoTemplate mongo;

    private String seedPlace() {
        String placeId = "place-" + UUID.randomUUID();
        Document doc = new Document()
                .append("place_id", placeId)
                .append("display_name", "장소")
                .append("address", "서울")
                .append("category", "tourist")
                .append("rating", 4.5)
                .append("photos", List.of("https://example.com/p1.jpg"));
        mongo.getCollection("place").insertOne(doc);
        return placeId;
    }

    private void addItem(String planId, String userId, int day, String placeId) throws Exception {
        mockMvc.perform(post("/api/tour/plans/" + planId + "/items")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("day_number", day, "place_id", placeId, "visit_time", "11:00")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("remove_day → travel_days 불변(gap 보존), 다른 day 항목 유지, 이후 add_day 는 max+1")
    void removeDayPreservesGapThenAddDayIsMaxPlusOne() throws Exception {
        String user = fixtures.createActiveUser("plan유저");
        String placeId = seedPlace();

        // travel_days=3, day1 에 1개 항목으로 생성
        String body = """
                {
                  "title": "3일 플랜",
                  "travel_days": 3,
                  "items": [ { "day_number": 1, "place_id": "%s", "visit_time": "10:00" } ]
                }
                """.formatted(placeId);
        MvcResult res = mockMvc.perform(post("/api/tour/plans")
                        .with(auth(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String planId = idFrom(res, "plan_id");

        // day2, day3 에 항목 추가
        addItem(planId, user, 2, placeId);
        addItem(planId, user, 3, placeId);

        // day2 삭제
        mockMvc.perform(delete("/api/tour/plans/" + planId + "/days/{day}", 2)
                        .with(auth(user)))
                .andExpect(status().isOk());

        // travel_days 불변(3), day2 항목만 사라지고 day1/day3 유지(gap 보존)
        mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.travel_days").value(3))
                .andExpect(jsonPath("$.items[?(@.day_number==2)]", hasSize(0)))
                .andExpect(jsonPath("$.items[?(@.day_number==1)]", hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.day_number==3)]", hasSize(1)));

        // add_day → travel_days = max+1 = 4 (gap 재사용 안 함)
        mockMvc.perform(post("/api/tour/plans/" + planId + "/days")
                        .with(auth(user)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/tour/plans/" + planId)
                        .with(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.travel_days").value(4));
    }
}
