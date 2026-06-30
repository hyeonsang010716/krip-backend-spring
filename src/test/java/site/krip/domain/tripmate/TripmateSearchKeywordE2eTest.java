package site.krip.domain.tripmate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여행메이트 검색어 검증 E2E.
 * 빈/공백 검색어는 400(전체조회·빈 검색기록 저장 방지), 정상 검색어는 200.
 */
@DisplayName("트립메이트 검색어 검증 — 빈/공백 keyword 400")
class TripmateSearchKeywordE2eTest extends IntegrationTestSupport {

    private static final String SEARCH = "/api/tripmate/posts/search";

    @Test
    @DisplayName("빈 검색어(keyword=) → 400")
    void emptyKeyword() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(get(SEARCH).param("keyword", "")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("공백만 검색어 → 400")
    void blankKeyword() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(get(SEARCH).param("keyword", "   ")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("정상 검색어 → 200, posts 배열 반환")
    void validKeyword() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(get(SEARCH).param("keyword", "서울")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").isArray());
    }
}
