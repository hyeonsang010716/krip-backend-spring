package site.krip.domain.auth.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

/** 로그아웃 — 로그인 쿠키 만료. */
@RestController
@RequestMapping("/api/auth/logout")
public class LogoutController {

    private final LoginCookieFactory cookieFactory;

    public LogoutController(LoginCookieFactory cookieFactory) {
        this.cookieFactory = cookieFactory;
    }

    @PostMapping
    public ResponseEntity<MessageResponse> logout(@CurrentUserId String userId) {
        ResponseCookie expired = cookieFactory.expired();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(new MessageResponse("로그아웃 되었습니다."));
    }
}
