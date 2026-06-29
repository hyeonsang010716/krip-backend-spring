package site.krip.domain.tripmate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tripmate 임시저장(draft) + 검색 기록(search-history) E2E.
 * draft 경로: {@code /api/tripmate/posts/draft}, 검색기록 경로: {@code /api/tripmate/search-history}.
 */
class TripmateDraftAndSearchHistoryE2eTest extends IntegrationTestSupport {

    // ──────────────────── 임시저장(draft) ────────────────────

    @Test
    @DisplayName("draft 저장(PUT)→조회(GET)→삭제(DELETE) 흐름")
    void draftLifecycle() throws Exception {
        String userId = fixtures.createActiveUser("임시저장유저");

        String draftBody = json(
                "title", "임시 제목",
                "content", "임시 본문 내용",
                "preferred_age_min", 20,
                "preferred_age_max", 30,
                "preferred_gender", "female",
                "region", "강릉",
                "travel_start_date", "2026-11-01",
                "travel_end_date", "2026-11-03",
                "companion_type", "couple",
                "image_urls", List.of());

        // 저장 (PUT) → 200
        mockMvc.perform(put("/api/tripmate/posts/draft")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("임시 제목"))
                .andExpect(jsonPath("$.region").value("강릉"))
                .andExpect(jsonPath("$.companion_type").value("couple"));

        // 조회 (GET) → 200, 저장한 값
        mockMvc.perform(get("/api/tripmate/posts/draft")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("임시 제목"))
                .andExpect(jsonPath("$.preferred_gender").value("female"));

        // 삭제 (DELETE) → 200
        mockMvc.perform(delete("/api/tripmate/posts/draft")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("저장한 적 없는 draft 조회 → 200 (본문 JSON null)")
    void getEmptyDraft() throws Exception {
        String userId = fixtures.createActiveUser();
        // 저장된 draft 가 없으면 Optional.empty → 200 + JSON null 직렬화.
        mockMvc.perform(get("/api/tripmate/posts/draft")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(content().string("null"));
    }

    @Test
    @DisplayName("draft 는 모든 필드 선택 — 빈 본문으로 저장 가능(200)")
    void saveEmptyDraft() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(put("/api/tripmate/posts/draft")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json()))
                .andExpect(status().isOk());
    }

    // ──────────────────── 검색 기록(search-history) ────────────────────

    /** 검색 API 호출은 검색 기록을 부수효과로 저장한다. */
    private void search(String userId, String keyword) throws Exception {
        mockMvc.perform(get("/api/tripmate/posts/search")
                        .with(auth(userId))
                        .param("keyword", keyword))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("검색→기록 목록→한 건 삭제→전체 삭제 흐름")
    void searchHistoryLifecycle() throws Exception {
        String userId = fixtures.createActiveUser("검색유저");

        search(userId, "부산");
        search(userId, "제주");

        // 목록 — 두 키워드 포함
        mockMvc.perform(get("/api/tripmate/search-history")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.histories").isArray())
                .andExpect(jsonPath("$.histories[?(@.search_name == '부산')]").exists())
                .andExpect(jsonPath("$.histories[?(@.search_name == '제주')]").exists());

        // 한 건 삭제 (?search_name=부산)
        mockMvc.perform(delete("/api/tripmate/search-history/one")
                        .with(auth(userId))
                        .param("search_name", "부산"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(get("/api/tripmate/search-history")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.histories[?(@.search_name == '부산')]").doesNotExist())
                .andExpect(jsonPath("$.histories[?(@.search_name == '제주')]").exists());

        // 전체 삭제
        mockMvc.perform(delete("/api/tripmate/search-history")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(get("/api/tripmate/search-history")
                        .with(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.histories.length()").value(0));
    }

    @Test
    @DisplayName("검색 keyword 파라미터 누락 → 400")
    void searchMissingKeyword() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(get("/api/tripmate/posts/search")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("검색 기록 한 건 삭제 시 search_name 누락 → 400")
    void deleteOneMissingParam() throws Exception {
        String userId = fixtures.createActiveUser();
        mockMvc.perform(delete("/api/tripmate/search-history/one")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }
}
