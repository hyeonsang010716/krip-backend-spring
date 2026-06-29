package site.krip.domain.tour;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 플랜/카드 수정 요청의 필수 키 존재 검증 E2E — {@code @JsonProperty(required=true)} 가 키 누락이면 400,
 * 키 존재(값 null)면 검증 통과 후 미존재 플랜이라 404 가 되는지 본다.
 */
class TourUpdateValidationE2eTest extends IntegrationTestSupport {

    private static final String NO_SUCH_PLAN = "/api/tour/plans/nope";
    private static final String NO_SUCH_ITEM = "/api/tour/plans/nope/items/nope";

    @Autowired
    private MongoTemplate mongo;

    /** place_id 로 최소 Place 문서를 시드(updateItem 은 장소 조회를 트랜잭션 밖에서 먼저 수행). */
    private void seedPlace(String placeId) {
        mongo.getCollection("place").insertOne(new Document()
                .append("place_id", placeId)
                .append("display_name", "시드장소")
                .append("address", "서울")
                .append("location", new Document("type", "Point")
                        .append("coordinates", List.of(126.97688, 37.57594))));
    }

    @Test
    @DisplayName("PATCH 플랜: title 키 누락 → 400")
    void updatePlanMissingTitleKey() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(patch(NO_SUCH_PLAN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH 플랜: title 키 존재(값 null) → 검증 통과, 미존재 플랜이라 404")
    void updatePlanNullTitlePassesValidation() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(patch(NO_SUCH_PLAN)
                        .contentType(MediaType.APPLICATION_JSON).content(json("title", null))
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT 카드: visit_time 키 누락 → 400")
    void updateItemMissingVisitTimeKey() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(put(NO_SUCH_ITEM)
                        .contentType(MediaType.APPLICATION_JSON).content(json("place_id", "p1"))
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT 카드: visit_time 키 존재(값 null) → 검증 통과, 미존재 플랜이라 404")
    void updateItemNullVisitTimePassesValidation() throws Exception {
        String userId = fixtures.createActiveUser();
        // updateItem 은 장소 조회(Mongo)를 먼저 하므로, 검증 통과 후 404(카드 미존재)에 도달하려면 place 존재 필요.
        seedPlace("p1");
        mockMvc.perform(put(NO_SUCH_ITEM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("place_id", "p1", "visit_time", null))
                        .with(auth(userId)))
                .andExpect(status().isNotFound());
    }
}
