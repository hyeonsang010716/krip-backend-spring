package site.krip.domain.auth.controller;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import site.krip.global.config.AuthProperties;

/** 로그인 JWT 쿠키 생성/만료 — httpOnly + secure + SameSite=None + path=/. */
@Component
public class LoginCookieFactory {

    private final String cookieName;
    private final long maxAgeSeconds;

    public LoginCookieFactory(AuthProperties props) {
        this.cookieName = props.jwt().cookieName();
        this.maxAgeSeconds = props.jwt().expirationSeconds();
    }

    public ResponseCookie create(String token) {
        return ResponseCookie.from(cookieName, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    public ResponseCookie expired() {
        return ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(0)
                .build();
    }
}
