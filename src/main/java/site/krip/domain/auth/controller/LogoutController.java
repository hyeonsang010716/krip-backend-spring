package site.krip.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.auth.port.UserPurgeCachePort;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.auth.RequestAttributes;
import site.krip.global.auth.jwt.TokenRevocationService;
import site.krip.global.common.dto.MessageResponse;

import java.time.Instant;

/** 로그아웃 — 로그인 쿠키 만료 + 현재 토큰 jti 폐기. */
@RestController
@RequestMapping("/api/auth/logout")
@RequiredArgsConstructor
public class LogoutController {

    private final LoginCookieFactory cookieFactory;
    private final TokenRevocationService revocation;
    private final UserPurgeCachePort chatSessions;

    @PostMapping
    public ResponseEntity<MessageResponse> logout(@CurrentUserId String userId, HttpServletRequest request) {
        // 헤더 토큰(앱)은 클라이언트가 폐기하므로 쿠키 만료만으로 부족 — jti 를 서버에서 폐기.
        Object jti = request.getAttribute(RequestAttributes.JWT_JTI);
        Object exp = request.getAttribute(RequestAttributes.JWT_EXP);
        if (jti instanceof String j && exp instanceof Instant e) {
            revocation.revoke(j, e);
            // 폐기와 동시에 이 토큰의 활성 WS 세션을 즉시 종료 — 핸드셰이크는 새 연결만 막으므로.
            chatSessions.revokeSessionsForToken(userId, j);
        }
        ResponseCookie expired = cookieFactory.expired();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(new MessageResponse("로그아웃 되었습니다."));
    }
}
