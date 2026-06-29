package site.krip.domain.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;
import site.krip.domain.auth.entity.OAuthProvider;
import site.krip.domain.auth.oauth.OAuthStateService;
import site.krip.domain.auth.service.OAuthCallbackService;
import site.krip.support.IntegrationTestSupport;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth 콜백 실패 경로 — 코드 교환/내부 실패가 JSON 500 이 아니라 FE 로 {@code ?status=} 리다이렉트되는지.
 * 유효 state 쿠키로 통과 후 {@link OAuthCallbackService} 를 mock 해 실패 주입(provider_error=교환실패, error=내부예외).
 */
class OAuthCallbackErrorRedirectE2eTest extends IntegrationTestSupport {

    @MockitoBean
    private OAuthCallbackService callbackService;

    @Autowired
    private OAuthStateService stateService;

    private Cookie stateCookie(OAuthStateService.Issued issued) {
        return new Cookie("oauth_state", issued.cookie().getValue());
    }

    @Test
    @DisplayName("웹 콜백 — Google 교환 실패 → status=provider_error 리다이렉트(500 아님)")
    void webProviderError() throws Exception {
        OAuthStateService.Issued st = stateService.create("server", "google");
        when(callbackService.parseProvider("google")).thenReturn(OAuthProvider.GOOGLE);
        when(callbackService.exchangeAndRegister(eq(OAuthProvider.GOOGLE), any(), eq("dummy-code")))
                .thenThrow(new RestClientException("google token endpoint down"));

        mockMvc.perform(get("/api/auth/login/callback")
                        .param("code", "dummy-code")
                        .param("state", st.state())
                        .cookie(stateCookie(st)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("https://krip.site")))
                .andExpect(header().string("Location", containsString("status=provider_error")));
    }

    @Test
    @DisplayName("웹 콜백 — 내부 예외 → status=error 리다이렉트(500 아님)")
    void webInternalError() throws Exception {
        OAuthStateService.Issued st = stateService.create("server", "google");
        when(callbackService.parseProvider("google")).thenReturn(OAuthProvider.GOOGLE);
        when(callbackService.exchangeAndRegister(eq(OAuthProvider.GOOGLE), any(), eq("dummy-code")))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(get("/api/auth/login/callback")
                        .param("code", "dummy-code")
                        .param("state", st.state())
                        .cookie(stateCookie(st)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("status=error")));
    }

    @Test
    @DisplayName("앱 콜백 — Google 교환 실패 → 딥링크 status=provider_error 리다이렉트")
    void appProviderError() throws Exception {
        OAuthStateService.Issued st = stateService.create("app", "google");
        when(callbackService.parseProvider("google")).thenReturn(OAuthProvider.GOOGLE);
        when(callbackService.exchangeAndRegister(eq(OAuthProvider.GOOGLE), any(), eq("dummy-code")))
                .thenThrow(new RestClientException("google userinfo down"));

        mockMvc.perform(get("/api/auth/login/app/callback")
                        .param("code", "dummy-code")
                        .param("state", st.state())
                        .cookie(stateCookie(st)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("krip://auth/callback")))
                .andExpect(header().string("Location", containsString("status=provider_error")));
    }
}
