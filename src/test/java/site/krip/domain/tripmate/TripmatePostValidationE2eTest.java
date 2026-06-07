package site.krip.domain.tripmate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate 게시글 교차 필드 검증 E2E (M7) — 나이 min&gt;max, 종료일&lt;시작일 → 400.
 * per-field 제약은 통과하지만 record {@code @AssertTrue} 가 거부하는 경계를 검증한다.
 */
class TripmatePostValidationE2eTest extends IntegrationTestSupport {

    private static final String CREATE = "/api/tripmate/posts";

    /** age min/max, 날짜 start/end 만 파라미터화한 유효 본문 베이스. */
    private static String body(int ageMin, int ageMax, String start, String end) {
        return """
                {
                  "title": "동행 구해요",
                  "content": "여행 동행을 찾습니다. 함께 가실 분 환영합니다.",
                  "preferred_age_min": %d,
                  "preferred_age_max": %d,
                  "preferred_gender": "any",
                  "region": "부산",
                  "travel_start_date": "%s",
                  "travel_end_date": "%s",
                  "companion_type": "friend",
                  "image_urls": []
                }
                """.formatted(ageMin, ageMax, start, end);
    }

    @Test
    @DisplayName("생성 — 선호 나이 min > max → 400")
    void ageRangeReversed() throws Exception {
        String userId = fixtures.createActiveUser("나이역전");

        mockMvc.perform(post(CREATE)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(40, 20, "2026-09-01", "2026-09-07")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("나이")));
    }

    @Test
    @DisplayName("생성 — 여행 종료일 < 시작일 → 400")
    void dateRangeReversed() throws Exception {
        String userId = fixtures.createActiveUser("날짜역전");

        mockMvc.perform(post(CREATE)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(20, 35, "2026-09-07", "2026-09-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("종료일")));
    }

    @Test
    @DisplayName("생성 — 경계 동일값(min==max, start==end) → 201")
    void equalBoundariesAllowed() throws Exception {
        String userId = fixtures.createActiveUser("경계동일");

        mockMvc.perform(post(CREATE)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(30, 30, "2026-09-01", "2026-09-01")))
                .andExpect(status().isCreated());
    }
}
