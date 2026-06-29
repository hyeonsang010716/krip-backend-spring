package site.krip.domain.auth.oauth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.krip.global.common.exception.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OAuthStateService — state nonce ↔ 바인딩 쿠키 상수시간 비교(CSRF 방어) 검증.
 * 실제 방어선인 nonce 비교(일치/불일치/쿠키부재) — 잘못된 필드 비교·쿠키 검사 제거 회귀를 잡는다.
 */
class OAuthStateServiceTest {

    private final OAuthStateService svc = new OAuthStateService();

    private static HttpServletRequest requestWithCookie(String value) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getCookies()).thenReturn(value == null ? null
                : new Cookie[]{new Cookie("oauth_state", value)});
        return req;
    }

    @Test
    @DisplayName("state nonce 와 쿠키가 일치하면 routing/provider 를 복원한다")
    void acceptsMatchingNonce() {
        OAuthStateService.Parsed parsed =
                svc.verify("server:google:nonce123", requestWithCookie("nonce123"));

        assertThat(parsed.routing()).isEqualTo("server");
        assertThat(parsed.provider()).isEqualTo("google");
    }

    @Test
    @DisplayName("쿠키 nonce 가 다르면 CSRF 의심으로 거부한다")
    void rejectsMismatchedNonce() {
        assertThatThrownBy(() -> svc.verify("server:google:nonce123", requestWithCookie("attacker")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("CSRF");
    }

    @Test
    @DisplayName("바인딩 쿠키가 없으면 거부한다")
    void rejectsMissingCookie() {
        assertThatThrownBy(() -> svc.verify("server:google:nonce123", requestWithCookie(null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("CSRF");
    }

    @Test
    @DisplayName("state 포맷이 잘못되면(세그먼트 수·빈 nonce·null) 거부한다")
    void rejectsMalformedState() {
        HttpServletRequest req = requestWithCookie("nonce123");
        assertThatThrownBy(() -> svc.verify("server:google", req)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> svc.verify("server:google:", req)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> svc.verify(null, req)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("create 가 발급한 state·쿠키는 서로 verify 된다 (round-trip)")
    void issuedStateAndCookieVerify() {
        OAuthStateService.Issued issued = svc.create("local", "google");
        String boundNonce = issued.cookie().getValue();

        OAuthStateService.Parsed parsed = svc.verify(issued.state(), requestWithCookie(boundNonce));

        assertThat(parsed.routing()).isEqualTo("local");
        assertThat(parsed.provider()).isEqualTo("google");
    }
}
