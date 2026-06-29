package site.krip.domain.tour;

import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import site.krip.support.IntegrationTestSupport;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;
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

    /** 헬퍼(json) 접근을 위해 테스트 인스턴스를 받아 요청을 조립한다. */
    @FunctionalInterface
    interface Req {
        MockHttpServletRequestBuilder build(TourUpdateValidationE2eTest t);
    }

    /** place_id 로 최소 Place 문서를 시드(updateItem 은 장소 조회를 트랜잭션 밖에서 먼저 수행). 멱등 — 반복 호출 무해. */
    private void seedPlace(String placeId) {
        mongo.getCollection("place").replaceOne(new Document("place_id", placeId),
                new Document()
                        .append("place_id", placeId)
                        .append("display_name", "시드장소")
                        .append("address", "서울")
                        .append("location", new Document("type", "Point")
                                .append("coordinates", List.of(126.97688, 37.57594))),
                new ReplaceOptions().upsert(true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredKeyCases")
    @DisplayName("수정 요청 필수 키 — 누락 → 400 / 값 null 은 검증 통과(미존재라 404)")
    void requiredKeyValidation(String label, Req req, int expectedStatus) throws Exception {
        String userId = fixtures.createActiveUser();
        seedPlace("p1"); // visit_time=null 케이스가 장소 조회를 통과해 404 에 닿도록(타 케이스엔 무해).

        mockMvc.perform(req.build(this)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(auth(userId)))
                .andExpect(status().is(expectedStatus));
    }

    static Stream<Arguments> requiredKeyCases() {
        return Stream.of(
                arguments("PATCH 플랜: title 키 누락 → 400",
                        (Req) t -> patch(NO_SUCH_PLAN).content(t.json()), 400),
                arguments("PATCH 플랜: title 키 존재(null) → 404",
                        (Req) t -> patch(NO_SUCH_PLAN).content(t.json("title", null)), 404),
                arguments("PUT 카드: visit_time 키 누락 → 400",
                        (Req) t -> put(NO_SUCH_ITEM).content(t.json("place_id", "p1")), 400),
                arguments("PUT 카드: visit_time 키 존재(null) → 404",
                        (Req) t -> put(NO_SUCH_ITEM).content(t.json("place_id", "p1", "visit_time", null)), 404));
    }
}
