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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * remove_day 가 plan 의 {@code updated_at} 을 갱신하는지 검증한다(회귀).
 *
 * <p>벌크 삭제({@code @Modifying(clearAutomatically=true)})가 영속성 컨텍스트를 비워 plan 을 detach 시키면
 * 이후 {@code plan.touch()} 가 무효화돼 updated_at 갱신이 유실됐었다. 목록은 {@code updated_at desc} 정렬이므로,
 * "더 먼저 만든 플랜의 day 를 삭제하면 그 플랜이 목록 맨 앞으로 올라온다"로 갱신 여부를 행위로 검증한다.
 */
class TourRemoveDayTouchE2eTest extends IntegrationTestSupport {

    @Autowired
    private ObjectMapper objectMapper;

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

    private String createPlan(String user, String title, int travelDays, String placeId) throws Exception {
        String body = """
                {
                  "title": "%s",
                  "travel_days": %d,
                  "items": [ { "day_number": 1, "place_id": "%s", "visit_time": "10:00" } ]
                }
                """.formatted(title, travelDays, placeId);
        MvcResult res = mockMvc.perform(post("/api/tour/plans")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("plan_id").asText();
    }

    @Test
    @DisplayName("remove_day → plan.updated_at 갱신(목록 최신순에서 해당 플랜이 맨 앞으로 이동)")
    void removeDayBumpsUpdatedAt() throws Exception {
        String user = fixtures.createActiveUser("plan유저");
        String placeId = seedPlace();

        // 먼저 A, 그다음 B 생성 → 초기 최신순은 [B, A]
        String planA = createPlan(user, "플랜 A", 2, placeId);
        String planB = createPlan(user, "플랜 B", 2, placeId);

        mockMvc.perform(get("/api/tour/plans")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[0].plan_id").value(planB))
                .andExpect(jsonPath("$.plans[1].plan_id").value(planA));

        // A 의 day 1 삭제 → A.updated_at 이 B 생성 시점보다 뒤로 갱신돼야 함
        mockMvc.perform(delete("/api/tour/plans/" + planA + "/days/{day}", 1)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(user)))
                .andExpect(status().is2xxSuccessful());

        // 갱신됐다면 최신순은 [A, B] 로 뒤집힌다
        mockMvc.perform(get("/api/tour/plans")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[0].plan_id").value(planA))
                .andExpect(jsonPath("$.plans[1].plan_id").value(planB));
    }
}
