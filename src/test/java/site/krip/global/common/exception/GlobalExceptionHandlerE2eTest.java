package site.krip.global.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import site.krip.support.IntegrationTestSupport;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전역 예외 처리 규약 E2E — Bean Validation/enum/JSON/누락 파라미터/미존재 라우트가 모두
 * {@code {"detail": ...}} 형태의 400/404 로 변환되는지 검증.
 */
@DisplayName("전역 예외 처리 — 검증/파싱/라우팅 에러 매핑")
class GlobalExceptionHandlerE2eTest extends IntegrationTestSupport {

    private String tripmateBody(String content, String companionType) {
        return json(
                "title", "예외 테스트",
                "content", content,
                "preferred_age_min", 20,
                "preferred_age_max", 40,
                "preferred_gender", "any",
                "region", "서울",
                "travel_start_date", "2026-10-01",
                "travel_end_date", "2026-10-05",
                "companion_type", companionType,
                "image_urls", List.of());
    }

    @Test
    @DisplayName("잘못된 enum 값 → 400 + detail")
    void invalidEnumReturns400() throws Exception {
        String u = fixtures.createActiveUser("enum유저");
        mockMvc.perform(post("/api/tripmate/posts")
                        .with(auth(u))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripmateBody("충분히 긴 본문 내용입니다.", "bogus")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("Bean Validation 위반(본문 10자 미만) → 400 + detail")
    void validationErrorReturns400() throws Exception {
        String u = fixtures.createActiveUser("valid유저");
        mockMvc.perform(post("/api/tripmate/posts")
                        .with(auth(u))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripmateBody("짧음", "friend")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("깨진 JSON 본문 → 400")
    void malformedJsonReturns400() throws Exception {
        String u = fixtures.createActiveUser("json유저");
        mockMvc.perform(post("/api/tripmate/posts")
                        .with(auth(u))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("필수 쿼리 파라미터 누락(keyword) → 400 + detail 에 파라미터명")
    void missingRequiredParamReturns400() throws Exception {
        String u = fixtures.createActiveUser("param유저");
        mockMvc.perform(get("/api/friend/search")
                        .with(auth(u)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("keyword")));
    }

    @Test
    @DisplayName("매핑되지 않은 라우트 → 404")
    void unknownRouteReturns404() throws Exception {
        String u = fixtures.createActiveUser("route유저");
        mockMvc.perform(get("/api/this-route-does-not-exist")
                        .with(auth(u)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("쿼리 파라미터 타입 변환 실패(미지원 OAuth 제공자) → 400")
    void typeMismatchReturns400() throws Exception {
        // OAuthProviderConverter 가 'kakao' 를 변환하지 못해 MethodArgumentTypeMismatchException → 400.
        // 로그인 진입점은 인증 필터 제외 경로이므로 컨트롤러 바인딩 단계까지 도달한다.
        mockMvc.perform(get("/api/auth/login")
                        .with(bearerOnly())
                        .param("type", "kakao"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }
}
