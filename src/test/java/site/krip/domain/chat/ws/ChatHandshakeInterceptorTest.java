package site.krip.domain.chat.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import site.krip.domain.auth.repository.UserRepository;
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
 * {@link ChatHandshakeInterceptor} 순수 단위 테스트.
 *
 * <p>WS 업그레이드는 accept() 전이라 RFC 6455 상 커스텀 close code 를 보낼 수 없으므로,
 * origin/auth/inactive 거부는 전부 <b>HTTP 403</b> 으로 일관되게 처리한다(토큰 없음/무효도 401 아닌 403).
 */
class ChatHandshakeInterceptorTest {

    private static final String SECRET = "test-login-jwt-secret-value-1234567890";
    private static final String ALLOWED_ORIGIN = "https://app.test";
    private static final String APP_ORIGIN = "capacitor://localhost";

    private final RegisteredCacheManager registeredCache = mock(RegisteredCacheManager.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final TokenRevocationService revocation = mock(TokenRevocationService.class);
    private final ChatHandshakeInterceptor interceptor = newInterceptor();

    private ChatHandshakeInterceptor newInterceptor() {
        AuthProperties.Jwt jwt = new AuthProperties.Jwt(SECRET, 7, "access_token");
        AuthProperties authProps = new AuthProperties("dev-access-token", jwt, 300L);
        JwtProvider jwtProvider = new JwtProvider(authProps, java.time.Clock.systemUTC());
        CorsProperties corsProps = new CorsProperties(List.of(ALLOWED_ORIGIN), List.of(APP_ORIGIN));
        return new ChatHandshakeInterceptor(jwtProvider, revocation, authProps, corsProps,
                registeredCache, userRepository);
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

    private JwtProvider tokenIssuer() {
        AuthProperties.Jwt jwt = new AuthProperties.Jwt(SECRET, 7, "access_token");
        return new JwtProvider(new AuthProperties("dev-access-token", jwt, 300L), java.time.Clock.systemUTC());
    }

    @Test
    @DisplayName("허용 Origin + 토큰 없음 → 핸드셰이크 403 거부 (401 아님)")
    void noTokenRejectedWith403() {
        ServerHttpRequest request = requestWith(ALLOWED_ORIGIN, null);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean ok = interceptor.beforeHandshake(request, response, null, new HashMap<>());

        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("허용 Origin + 무효 토큰(서명불가) → 핸드셰이크 403 거부")
    void invalidTokenRejectedWith403() {
        ServerHttpRequest request = requestWith(ALLOWED_ORIGIN, "krip.chat.v1, auth.not-a-real-jwt");
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean ok = interceptor.beforeHandshake(request, response, null, new HashMap<>());

        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("허용되지 않은 Origin → 403 거부 (모든 핸드셰이크 거부는 403 일관)")
    void disallowedOriginRejectedWith403() {
        ServerHttpRequest request = requestWith("https://evil.test", "auth." + tokenIssuer().issue("USER_x"));
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean ok = interceptor.beforeHandshake(request, response, null, new HashMap<>());

        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("허용 Origin + 유효 토큰 + ACTIVE(캐시 hit) → 통과(true) + user attribute 주입")
    void validTokenActiveUserAccepted() {
        String userId = "USER_active";
        String token = tokenIssuer().issue(userId);
        when(registeredCache.exists(userId)).thenReturn(true);

        ServerHttpRequest request = requestWith(ALLOWED_ORIGIN, "auth." + token);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(request, response, null, attributes);

        assertThat(ok).isTrue();
        assertThat(attributes).containsEntry(ChatHandshakeInterceptor.ATTR_WS_USER, userId);
        verify(response, never()).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("네이티브 앱 Origin(capacitor) + 유효 토큰 + ACTIVE → 통과 (앱 origin 화이트리스트)")
    void appOriginActiveUserAccepted() {
        String userId = "USER_app";
        String token = tokenIssuer().issue(userId);
        when(registeredCache.exists(userId)).thenReturn(true);

        ServerHttpRequest request = requestWith(APP_ORIGIN, "auth." + token);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(request, response, null, attributes);

        assertThat(ok).isTrue();
        assertThat(attributes).containsEntry(ChatHandshakeInterceptor.ATTR_WS_USER, userId);
        verify(response, never()).setStatusCode(HttpStatus.FORBIDDEN);
    }
}
