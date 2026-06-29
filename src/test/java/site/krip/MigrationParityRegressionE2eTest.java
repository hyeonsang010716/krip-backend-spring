package site.krip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import site.krip.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마이그레이션 패리티 보정 회귀 — 추가한 400 분기들이 깨지지 않게 고정한다.
 *
 * <p>커버: 잘못된 place 커서 400 / 검색기록 빈 검색어 삭제 400(friend·tour·tripmate) / 빈 이미지 업로드 400.
 */
class MigrationParityRegressionE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("tour 장소 — 잘못된 형식의 cursor → 400 (500 아님)")
    void tourPlaceMalformedCursor() throws Exception {
        String userId = fixtures.createActiveUser();

        mockMvc.perform(get("/api/tour/places")
                        .param("keyword", "cafe")
                        .param("cursor", "garbage-no-colon")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "{0} 빈 search_name 삭제 → 400")
    @ValueSource(strings = {
            "/api/friend/search/history/one",
            "/api/tour/search-history/one",
            "/api/tripmate/search-history/one"})
    @DisplayName("검색기록 — 빈 search_name 삭제 → 400 (friend·tour·tripmate)")
    void searchHistoryBlankDelete(String path) throws Exception {
        String userId = fixtures.createActiveUser();

        mockMvc.perform(delete(path)
                        .param("search_name", " ")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("tripmate 이미지 — 파일 없는 업로드 → 400")
    void tripmateImageEmptyUpload() throws Exception {
        String userId = fixtures.createActiveUser();

        mockMvc.perform(multipart("/api/tripmate/images")
                        .with(auth(userId)))
                .andExpect(status().isBadRequest());
    }
}
