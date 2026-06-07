package site.krip.domain.chat.ws;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserStatus;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.global.auth.jwt.JwtProvider;
import site.krip.global.auth.jwt.TokenRevocationService;
import site.krip.global.cache.RegisteredCacheManager;
import site.krip.global.config.AuthProperties;
import site.krip.global.config.CorsProperties;

import java.util.List;
import java.util.Map;

/**
 * WS 핸드셰이크 인증/인가 — origin/JWT/active 가드.
 *
 * <p>WS 업그레이드는 서블릿 필터 체인의 인증을 거치지 않으므로 여기서 직접 수행한다:
 * Origin 화이트리스트 → JWT(쿠키 {@code utk} 또는 {@code auth.<jwt>} 서브프로토콜) → ACTIVE+2차가입 확인.
 * 통과 시 user_id / token_jti 를 attributes 로 넘긴다.
 *
 * <p>실패는 핸드셰이크 거부 = <b>HTTP 403</b>. RFC 6455 상 101 완료 전에는 WS close 프레임을 보낼 수 없어
 * origin/auth/inactive 를 close code 로 구분할 수 없다. 구분 가능한 close code({@code 4001} 세션 만료/refresh 실패)는
 * accept 이후 receive loop(ChatWebSocketHandler)에서 송신한다.
 */
@Component
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ChatHandshakeInterceptor.class);
    private static final String SUBPROTOCOL_AUTH_PREFIX = "auth.";

    public static final String ATTR_WS_USER = "ws_user_id";
    public static final String ATTR_WS_JTI = "ws_token_jti";

    private final JwtProvider jwtProvider;
    private final TokenRevocationService revocation;
    private final AuthProperties authProps;
    private final CorsProperties corsProps;
    private final RegisteredCacheManager registeredCache;
    private final UserRepository userRepository;

    public ChatHandshakeInterceptor(JwtProvider jwtProvider, TokenRevocationService revocation,
                                    AuthProperties authProps, CorsProperties corsProps,
                                    RegisteredCacheManager registeredCache, UserRepository userRepository) {
        this.jwtProvider = jwtProvider;
        this.revocation = revocation;
        this.authProps = authProps;
        this.corsProps = corsProps;
        this.registeredCache = registeredCache;
        this.userRepository = userRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null || !isAllowedOrigin(origin)) {
            log.warn("WS 연결 거부 — 허용되지 않은 Origin: {}", origin);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        String token = extractJwt(request);
        if (token == null) {
            // 핸드셰이크 거부 = HTTP 403 (구분 가능한 close code 는 accept 이후 receive loop 에서).
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        JwtProvider.ParsedToken parsed;
        try {
            parsed = jwtProvider.parse(token);
        } catch (Exception e) {
            // 핸드셰이크 거부 = HTTP 403 (구분 가능한 close code 는 accept 이후 receive loop 에서).
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        String userId = parsed.userId();
        if (userId == null || userId.isEmpty()) {
            // 핸드셰이크 거부 = HTTP 403 (구분 가능한 close code 는 accept 이후 receive loop 에서).
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        if (revocation.isRevoked(parsed.jti())) {
            log.warn("WS 연결 거부 — 폐기된 토큰: user_id={}", userId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        if (!isUserActive(userId)) {
            log.warn("WS 연결 거부 — INACTIVE/미가입 유저: user_id={}", userId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        // jti claim 미사용 — token 앞 32자 fallback (refresh 와 동일 로직).
        String jti = token.length() > 32 ? token.substring(0, 32) : token;
        attributes.put(ATTR_WS_USER, userId);
        attributes.put(ATTR_WS_JTI, jti);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    /** 웹(HTTP CORS) origin + 네이티브 앱 전용 origin 화이트리스트. */
    private boolean isAllowedOrigin(String origin) {
        List<String> web = corsProps.allowedOrigins();
        if (web != null && web.contains(origin)) {
            return true;
        }
        List<String> app = corsProps.appAllowedOrigins();
        return app != null && app.contains(origin);
    }

    private String extractJwt(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servlet) {
            HttpServletRequest req = servlet.getServletRequest();
            Cookie[] cookies = req.getCookies();
            if (cookies != null) {
                for (Cookie c : cookies) {
                    if (authProps.jwt().cookieName().equals(c.getName()) && c.getValue() != null
                            && !c.getValue().isEmpty()) {
                        return c.getValue();
                    }
                }
            }
        }
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols != null) {
            for (String raw : protocols) {
                for (String p : raw.split(",")) {
                    String proto = p.trim();
                    if (proto.startsWith(SUBPROTOCOL_AUTH_PREFIX)) {
                        String token = proto.substring(SUBPROTOCOL_AUTH_PREFIX.length());
                        if (!token.isEmpty()) {
                            return token;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** ACTIVE + 2차 가입 완료 — REGISTERED 캐시를 HTTP 와 공유. DB 장애 시 fail-closed. */
    private boolean isUserActive(String userId) {
        if (registeredCache.exists(userId)) {
            return true;
        }
        User user;
        try {
            user = userRepository.findByIdWithProfile(userId).orElse(null);
        } catch (Exception e) {
            log.warn("WS status 가드 — DB 조회 실패 (fail-closed): user_id={}", userId, e);
            return false;
        }
        if (user == null || user.getStatus() != UserStatus.ACTIVE || user.getDetail() == null) {
            return false;
        }
        registeredCache.setFlag(userId);
        return true;
    }
}
