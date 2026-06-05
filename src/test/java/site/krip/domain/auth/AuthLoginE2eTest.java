package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.support.IntegrationTestSupport;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth 로그인 진입/콜백 E2E — 경로 {@code /api/auth/login}, {@code /api/auth/login/app}.
 *
 * <p>진입(authorize URL 빌드)과 콜백의 입력 검증은 외부 네트워크 없이 검증 가능하다(provider/state 파싱이
 * 토큰 교환보다 먼저 일어남). 콜백의 code→token→userinfo 해피패스는 외부 Google 의존이라 제외하고,
 * 가입 분기는 {@code SignupServiceIntegrationTest} 가 직접 덮는다.
 *
 * <p>커버: 진입 302(google) / 미지원 provider 400 / 콜백 미지원 provider 400 / 콜백 잘못된 state 400.
 */
class AuthLoginE2eTest extends IntegrationTestSupport {

    @Test
    @DisplayName("웹 로그인 진입 → 302, Google authorize URL 로 리다이렉트")
    void webLoginRedirects() throws Exception {
        mockMvc.perform(get("/api/auth/login").param("type", "google")
                        .header("Authorization", bearer()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com")));
    }

    @Test
    @DisplayName("앱 로그인 진입 → 302, Google authorize URL 로 리다이렉트")
    void appLoginRedirects() throws Exception {
        mockMvc.perform(get("/api/auth/login/app").param("type", "google")
                        .header("Authorization", bearer()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com")));
    }

    @Test
    @DisplayName("진입 시 미지원 provider(type=kakao) → 400")
    void loginUnsupportedProvider() throws Exception {
        mockMvc.perform(get("/api/auth/login").param("type", "kakao")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("웹 콜백 — state 의 provider 가 미지원 → 400 (500 아님)")
    void webCallbackUnsupportedProvider() throws Exception {
        mockMvc.perform(get("/api/auth/login/callback")
                        .param("code", "dummy-code")
                        .param("state", "server:kakao")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("웹 콜백 — 잘못된 state(콜론 없음) → 400")
    void webCallbackMalformedState() throws Exception {
        mockMvc.perform(get("/api/auth/login/callback")
                        .param("code", "dummy-code")
                        .param("state", "no-colon-here")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("앱 콜백 — state 의 provider 가 미지원 → 400 (500 아님)")
    void appCallbackUnsupportedProvider() throws Exception {
        mockMvc.perform(get("/api/auth/login/app/callback")
                        .param("code", "dummy-code")
                        .param("state", "app:kakao")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("앱 콜백 — state prefix 가 app 이 아님 → 400")
    void appCallbackWrongStatePrefix() throws Exception {
        mockMvc.perform(get("/api/auth/login/app/callback")
                        .param("code", "dummy-code")
                        .param("state", "server:google")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }
}
