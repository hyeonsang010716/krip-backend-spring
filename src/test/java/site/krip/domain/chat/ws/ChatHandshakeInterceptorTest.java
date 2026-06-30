package site.krip.domain.chat.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import site.krip.domain.auth.port.UserQueryPort;
import site.krip.global.auth.jwt.JwtProvider;
import site.krip.global.auth.jwt.TokenRevocationService;
import site.krip.global.cache.RegisteredCacheManager;
import site.krip.global.config.AuthProperties;
import site.krip.global.config.CorsProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatHandshakeInterceptor} 순수 단위 테스트 — accept() 전이라 RFC 6455 상 커스텀 close code 불가,
 * origin/auth/inactive 거부는 전부 HTTP 403 으로 일관(토큰 없음/무효도 401 아닌 403).
 */
@DisplayName("WS 핸드셰이크 — Origin/토큰/상태 검증 403")
class ChatHandshakeInterceptorTest {

    private static final String SECRET = "test-login-jwt-secret-value-1234567890";
    private static final String ALLOWED_ORIGIN = "https://app.test";
    private static final String APP_ORIGIN = "capacitor://localhost";
    private static final AuthProperties AUTH_PROPS =
            new AuthProperties("dev-access-token", new AuthProperties.Jwt(SECRET, 7, "access_token"), 300L);

    private final RegisteredCacheManager registeredCache = mock(RegisteredCacheManager.class);
    private final UserQueryPort userQuery = mock(UserQueryPort.class);
    private final TokenRevocationService revocation = mock(TokenRevocationService.class);
    // 인터셉터 와이어링과 토큰 발급에 동일 JwtProvider 사용(같은 SECRET) — 서명 정합성 보장.
    private final JwtProvider jwtProvider = new JwtProvider(AUTH_PROPS, java.time.Clock.systemUTC());
    private final ChatHandshakeInterceptor interceptor = newInterceptor();

    private ChatHandshakeInterceptor newInterceptor() {
        CorsProperties corsProps = new CorsProperties(List.of(ALLOWED_ORIGIN), List.of(APP_ORIGIN));
        return new ChatHandshakeInterceptor(jwtProvider, revocation, AUTH_PROPS, corsProps,
                registeredCache, userQuery);
    }

    /** Sec-WebSocket-Protocol / Origin 만 노출하는 비-서블릿 ServerHttpRequest mock(쿠키 분기는 건너뜀). */
    private ServerHttpRequest requestWith(String origin, String subprotocol) {
        HttpHeaders headers = new HttpHeaders();
        if (origin != null) {
            headers.setOrigin(origin);
        }
        if (subprotocol != null) {
            headers.add("Sec-WebSocket-Protocol", subprotocol);
        }
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    @Test
    @DisplayName("허용 Origin + 토큰 없음 → 핸드셰이크 403 거부 (401 아님)")
    void noTokenRejectedWith403() {
        // given
        ServerHttpRequest request = requestWith(ALLOWED_ORIGIN, null);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        // when
        boolean ok = interceptor.beforeHandshake(request, response, null, new HashMap<>());

        // then
        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("허용 Origin + 무효 토큰(서명불가) → 핸드셰이크 403 거부")
    void invalidTokenRejectedWith403() {
        // given
        ServerHttpRequest request = requestWith(ALLOWED_ORIGIN, "krip.chat.v1, auth.not-a-real-jwt");
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        // when
        boolean ok = interceptor.beforeHandshake(request, response, null, new HashMap<>());

        // then
        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("허용되지 않은 Origin → 403 거부 (모든 핸드셰이크 거부는 403 일관)")
    void disallowedOriginRejectedWith403() {
        // given
        ServerHttpRequest request = requestWith("https://evil.test", "auth." + jwtProvider.issue("USER_x"));
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        // when
        boolean ok = interceptor.beforeHandshake(request, response, null, new HashMap<>());

        // then
        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {ALLOWED_ORIGIN, APP_ORIGIN})
    @DisplayName("유효 토큰 + ACTIVE — 웹/앱 화이트리스트 Origin 둘 다 통과(true) + user attribute 주입")
    void validTokenActiveUserAccepted(String origin) {
        // given
        String userId = "USER_active";
        String token = jwtProvider.issue(userId);
        when(registeredCache.exists(userId)).thenReturn(true);

        ServerHttpRequest request = requestWith(origin, "auth." + token);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        // when
        boolean ok = interceptor.beforeHandshake(request, response, null, attributes);

        // then
        assertThat(ok).isTrue();
        assertThat(attributes).containsEntry(ChatHandshakeInterceptor.ATTR_WS_USER, userId);
        verify(response, never()).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("유효 토큰 + INACTIVE/미가입(캐시 miss + DB false) → 403 거부")
    void inactiveUserRejectedWith403() {
        // given
        String userId = "USER_inactive";
        String token = jwtProvider.issue(userId);
        when(registeredCache.exists(userId)).thenReturn(false);
        when(userQuery.isActiveRegistered(userId)).thenReturn(false);

        ServerHttpRequest request = requestWith(ALLOWED_ORIGIN, "auth." + token);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        // when
        boolean ok = interceptor.beforeHandshake(request, response, null, new HashMap<>());

        // then
        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }
}
