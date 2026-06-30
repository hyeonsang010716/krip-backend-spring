package site.krip.domain.publicshare;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.support.IntegrationTestSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 share E2E — 공유 토큰 발급 → 인증 없이 공개 endpoint 라운드트립(소유자 user_id 미노출).
 * 토큰 무효/손상/만료 → 400, 디코드됐으나 plan 삭제 → 404. JSON snake_case.
 */
@DisplayName("공개 공유 — 토큰 발급·무인증 조회·손상/삭제 처리")
class PublicShareE2eTest extends IntegrationTestSupport {

    @Autowired
    private MongoTemplate mongo;

    private String seedPlace(String displayName, String address) {
        String placeId = "place-" + UUID.randomUUID();
        Document doc = new Document()
                .append("place_id", placeId)
                .append("display_name", displayName)
                .append("address", address)
                .append("category", "tourist")
                .append("rating", 4.7)
                .append("photos", List.of("https://example.com/share.jpg"));
        mongo.getCollection("place").insertOne(doc);
        return placeId;
    }

    /** 플랜 생성 후 plan_id 반환. */
    private String createPlan(String userId, String placeId) throws Exception {
        String body = json(
                "title", "공유용 플랜",
                "travel_days", 2,
                "items", List.of(Map.of(
                        "day_number", 1, "place_id", placeId, "visit_time", "10:00")));
        MvcResult res = mockMvc.perform(post("/api/tour/plans")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(res, "plan_id");
    }

    /** 공유 토큰 발급(POST /plans/{id}/share, 201) 후 share_token 반환. */
    private String issueShareToken(String userId, String planId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/tour/plans/{planId}/share", planId)
                        .with(auth(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.share_token").exists())
                .andExpect(jsonPath("$.expires_at").exists())
                .andReturn();
        return idFrom(res, "share_token");
    }

    // ──────────────────── 라운드트립 ────────────────────

    @Test
    @DisplayName("공유 토큰 발급 → 공개 endpoint 조회(인증 없이) → 200, user_id 미노출")
    void shareRoundTrip() throws Exception {
        // given
        String userId = fixtures.createActiveUser("공유소유자");
        String placeId = seedPlace("불국사", "경주");
        String planId = createPlan(userId, placeId);
        String token = issueShareToken(userId, planId);

        // 공개 endpoint — Authorization/X-Auth-Token 헤더 없이 호출
        mockMvc.perform(get("/api/public/share/plan/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_id").value(planId))
                .andExpect(jsonPath("$.title").value("공유용 플랜"))
                .andExpect(jsonPath("$.travel_days").value(2))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].place_id").value(placeId))
                .andExpect(jsonPath("$.items[0].display_name").value("불국사"))
                // 소유자 식별 필드는 노출되지 않아야 함
                .andExpect(jsonPath("$.user_id").doesNotExist());
    }

    // ──────────────────── 오류 케이스 ────────────────────

    @ParameterizedTest(name = "\"{0}\" -> 400")
    // not-a-valid-jwt: 구조 자체 손상 / aaaa.bbbb.cccc: 점 3개로 JWT 형식만 흉내낸 가비지(서명 검증 실패)
    @ValueSource(strings = {"not-a-valid-jwt", "aaaa.bbbb.cccc"})
    @DisplayName("공개 endpoint — 손상/가비지 토큰 → 400")
    void badToken(String token) throws Exception {
        mockMvc.perform(get("/api/public/share/plan/{token}", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("공개 endpoint — 토큰 발급 후 plan 삭제됨 → 404")
    void planDeletedAfterShare() throws Exception {
        // given
        String userId = fixtures.createActiveUser("삭제예정");
        String placeId = seedPlace("석굴암", "경주");
        String planId = createPlan(userId, placeId);
        String token = issueShareToken(userId, planId);

        // 소유자가 플랜 삭제
        mockMvc.perform(delete("/api/tour/plans/{planId}", planId)
                        .with(auth(userId)))
                .andExpect(status().isOk());

        // 토큰 디코드는 성공하지만 plan 이 사라져 → 404
        mockMvc.perform(get("/api/public/share/plan/{token}", token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("공개 endpoint — 인증 헤더가 있어도(화이트리스트) 정상 200")
    void worksWithStrayHeaders() throws Exception {
        // given
        String userId = fixtures.createActiveUser();
        String placeId = seedPlace("첨성대", "경주");
        String planId = createPlan(userId, placeId);
        String token = issueShareToken(userId, planId);

        // when & then
        mockMvc.perform(get("/api/public/share/plan/{token}", token)
                        .with(bearerOnly()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_id").value(planId));
    }
}
