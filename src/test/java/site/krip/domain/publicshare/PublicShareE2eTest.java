package site.krip.domain.publicshare;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 공개 share E2E — tour 의 공유 토큰 발급 → 공개 endpoint 라운드트립 검증.
 *
 * <p>공개 endpoint {@code GET /api/public/share/plan/{token}} 는 인증 헤더 없이 동작해야 한다
 * (세 인증 필터가 {@code /api/public} 화이트리스트). 응답에 소유자(user_id) 가 노출되지 않는다.
 * 토큰 무효/손상/만료 → 400, 디코드는 됐으나 plan 삭제됨 → 404. 요청/응답 JSON snake_case.
 */
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
        String body = """
                {
                  "title": "공유용 플랜",
                  "travel_days": 2,
                  "items": [
                    { "day_number": 1, "place_id": "%s", "visit_time": "10:00" }
                  ]
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

    /** 공유 토큰 발급(POST /plans/{id}/share, 201) 후 share_token 반환. */
    private String issueShareToken(String userId, String planId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/tour/plans/" + planId + "/share")
                        .with(auth(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.share_token").exists())
                .andExpect(jsonPath("$.expires_at").exists())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("share_token").asText();
    }

    // ──────────────────── 라운드트립 ────────────────────

    @Test
    @DisplayName("공유 토큰 발급 → 공개 endpoint 조회(인증 없이) → 200, user_id 미노출")
    void shareRoundTrip() throws Exception {
        String userId = fixtures.createActiveUser("공유소유자");
        String placeId = seedPlace("불국사", "경주");
        String planId = createPlan(userId, placeId);
        String token = issueShareToken(userId, planId);

        // 공개 endpoint — Authorization/X-Auth-Token 헤더 없이 호출
        MvcResult res = mockMvc.perform(get("/api/public/share/plan/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_id").value(planId))
                .andExpect(jsonPath("$.title").value("공유용 플랜"))
                .andExpect(jsonPath("$.travel_days").value(2))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].place_id").value(placeId))
                .andExpect(jsonPath("$.items[0].display_name").value("불국사"))
                // 소유자 식별 필드는 노출되지 않아야 함
                .andExpect(jsonPath("$.user_id").doesNotExist())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertFalse(body.has("user_id"), "공개 응답에 user_id 가 없어야 한다");
    }

    // ──────────────────── 오류 케이스 ────────────────────

    @Test
    @DisplayName("공개 endpoint — 손상된 토큰 → 400")
    void invalidToken() throws Exception {
        mockMvc.perform(get("/api/public/share/plan/not-a-valid-jwt"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("공개 endpoint — garbage(JWT 구조 흉내) 토큰 → 400")
    void garbageToken() throws Exception {
        // 점 3개로 JWT 형식만 흉내낸 가비지 → 서명 검증 실패 → 400
        mockMvc.perform(get("/api/public/share/plan/aaaa.bbbb.cccc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("공개 endpoint — 토큰 발급 후 plan 삭제됨 → 404")
    void planDeletedAfterShare() throws Exception {
        String userId = fixtures.createActiveUser("삭제예정");
        String placeId = seedPlace("석굴암", "경주");
        String planId = createPlan(userId, placeId);
        String token = issueShareToken(userId, planId);

        // 소유자가 플랜 삭제
        mockMvc.perform(delete("/api/tour/plans/" + planId)
                        .with(auth(userId)))
                .andExpect(status().isOk());

        // 토큰 디코드는 성공하지만 plan 이 사라져 → 404
        mockMvc.perform(get("/api/public/share/plan/" + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("공개 endpoint — 인증 헤더가 있어도(화이트리스트) 정상 200")
    void worksWithStrayHeaders() throws Exception {
        String userId = fixtures.createActiveUser();
        String placeId = seedPlace("첨성대", "경주");
        String planId = createPlan(userId, placeId);
        String token = issueShareToken(userId, planId);

        mockMvc.perform(get("/api/public/share/plan/" + token)
                        .with(bearerOnly()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_id").value(planId));
    }
}
