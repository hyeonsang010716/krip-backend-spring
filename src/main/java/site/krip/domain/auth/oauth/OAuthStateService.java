package site.krip.domain.auth.oauth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import site.krip.global.common.exception.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * OAuth state CSRF 방어 — state 에 nonce 를 싣고, 같은 nonce 를 단명 쿠키로 브라우저에 바인딩한다.
 *
 * <p>콜백에서 state 의 nonce 와 쿠키를 상수시간 비교 — 공격자가 피해자 쿠키를 심을 수 없으므로
 * 로그인 CSRF/세션 고정을 차단한다. state 포맷: {@code routing:provider:nonce}.
 */
@Component
public class OAuthStateService {

    private static final String COOKIE = "oauth_state";
    private static final long TTL_SECONDS = 600;
    private static final String COOKIE_PATH = "/api/auth/login";

    /** 생성된 state 와 함께 내려줄 바인딩 쿠키. */
    public record Issued(String state, ResponseCookie cookie) {
    }

    /** 검증으로 복원한 라우팅·제공자 세그먼트. */
    public record Parsed(String routing, String provider) {
    }

    /** routing(server/local/app) + provider 로 state 생성 + 바인딩 쿠키 발급. */
    public Issued create(String routing, String provider) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String state = routing + ":" + provider + ":" + nonce;
        return new Issued(state, buildCookie(nonce, TTL_SECONDS));
    }

    /** state 의 nonce 가 바인딩 쿠키와 일치하는지 검증 후 routing/provider 반환. */
    public Parsed verify(String state, HttpServletRequest request) {
        String[] parts = state == null ? new String[0] : state.split(":", 3);
        if (parts.length != 3 || parts[2].isEmpty()) {
            throw ApiException.badRequest("잘못된 state 값");
        }
        String cookie = readCookie(request);
        if (cookie == null || !MessageDigest.isEqual(
                cookie.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.badRequest("state 검증 실패 (CSRF 의심)");
        }
        return new Parsed(parts[0], parts[1]);
    }

    /** 단일사용 후 state 쿠키 만료. */
    public ResponseCookie expiredCookie() {
        return buildCookie("", 0);
    }

    private ResponseCookie buildCookie(String value, long maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    private @Nullable String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty()) {
                return c.getValue();
            }
        }
        return null;
    }
}
