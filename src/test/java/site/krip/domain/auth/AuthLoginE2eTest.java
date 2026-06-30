package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import site.krip.support.IntegrationTestSupport;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth 로그인 진입/콜백 E2E ({@code /api/auth/login}, {@code /api/auth/login/app}).
 * 콜백 실패는 JSON 이 아니라 FE 로 {@code ?status=state_invalid} 리다이렉트(브라우저가 리다이렉트 중이므로).
 */
@DisplayName("OAuth 로그인 — 진입 리다이렉트·미지원 provider·state 검증")
class AuthLoginE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("웹 로그인 진입 → 302, Google authorize URL 로 리다이렉트")
    void webLoginRedirects() throws Exception {
        mockMvc.perform(get("/api/auth/login").param("type", "google")
                        .with(bearerOnly()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("accounts.google.com")));
    }

    @Test
    @DisplayName("앱 로그인 진입 → 302, Google authorize URL 로 리다이렉트")
    void appLoginRedirects() throws Exception {
        mockMvc.perform(get("/api/auth/login/app").param("type", "google")
                        .with(bearerOnly()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("accounts.google.com")));
    }

    @Test
    @DisplayName("진입 시 미지원 provider(type=kakao) → 400")
    void loginUnsupportedProvider() throws Exception {
        mockMvc.perform(get("/api/auth/login").param("type", "kakao")
                        .with(bearerOnly()))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "[{index}] {0} state={1} → {2} status=state_invalid")
    @CsvSource({
            // 웹 콜백: state 검증 실패 / 콜론 없는 malformed state
            "/api/auth/login/callback,      server:kakao,   https://krip.site",
            "/api/auth/login/callback,      no-colon-here,  status=state_invalid",
            // 앱 콜백: state 검증 실패 / app 아닌 prefix
            "/api/auth/login/app/callback,  app:kakao,      krip://auth/callback",
            "/api/auth/login/app/callback,  server:google,  status=state_invalid",
    })
    @DisplayName("콜백 — state 검증/포맷 실패 → FE·딥링크 리다이렉트(status=state_invalid)")
    void callbackStateInvalidRedirects(String path, String state, String locationFragment) throws Exception {
        mockMvc.perform(get(path)
                        .param("code", "dummy-code")
                        .param("state", state)
                        .with(bearerOnly()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString(locationFragment)))
                .andExpect(header().string("Location", containsString("status=state_invalid")));
    }
}
