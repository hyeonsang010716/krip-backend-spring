package site.krip.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.support.IntegrationTestSupport;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth 로그인 진입/콜백 E2E ({@code /api/auth/login}, {@code /api/auth/login/app}).
 * 콜백 실패는 JSON 이 아니라 FE 로 {@code ?status=state_invalid} 리다이렉트(브라우저가 리다이렉트 중이므로).
 */
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

    @Test
    @DisplayName("웹 콜백 — state 검증 실패 → JSON 아닌 FE 리다이렉트(status=state_invalid)")
    void webCallbackInvalidStateRedirects() throws Exception {
        mockMvc.perform(get("/api/auth/login/callback")
                        .param("code", "dummy-code")
                        .param("state", "server:kakao")
                        .with(bearerOnly()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("https://krip.site")))
                .andExpect(header().string("Location", containsString("status=state_invalid")));
    }

    @Test
    @DisplayName("웹 콜백 — 잘못된 state(콜론 없음) → FE 리다이렉트(status=state_invalid)")
    void webCallbackMalformedStateRedirects() throws Exception {
        mockMvc.perform(get("/api/auth/login/callback")
                        .param("code", "dummy-code")
                        .param("state", "no-colon-here")
                        .with(bearerOnly()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("status=state_invalid")));
    }

    @Test
    @DisplayName("앱 콜백 — state 검증 실패 → 딥링크 리다이렉트(status=state_invalid)")
    void appCallbackInvalidStateRedirects() throws Exception {
        mockMvc.perform(get("/api/auth/login/app/callback")
                        .param("code", "dummy-code")
                        .param("state", "app:kakao")
                        .with(bearerOnly()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("krip://auth/callback")))
                .andExpect(header().string("Location", containsString("status=state_invalid")));
    }

    @Test
    @DisplayName("앱 콜백 — state prefix 가 app 이 아님 → 딥링크 리다이렉트(status=state_invalid)")
    void appCallbackWrongStatePrefixRedirects() throws Exception {
        mockMvc.perform(get("/api/auth/login/app/callback")
                        .param("code", "dummy-code")
                        .param("state", "server:google")
                        .with(bearerOnly()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("status=state_invalid")));
    }
}
