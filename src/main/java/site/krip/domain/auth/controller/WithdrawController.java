package site.krip.domain.auth.controller;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.auth.dto.response.WithdrawResponse;
import site.krip.domain.auth.service.WithdrawService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;
import site.krip.global.support.IsoTimestamp;

import java.time.Instant;

/**
 * 회원 탈퇴/취소. 이 prefix 는 RegisterCheckFilter 제외 대상이라 INACTIVE 유저도 cancel 에 도달한다.
 */
@RestController
@RequestMapping("/api/auth/withdraw")
@Slf4j
@RequiredArgsConstructor
public class WithdrawController {

    private final WithdrawService withdrawService;
    private final LoginCookieFactory cookieFactory;

    /** 탈퇴 요청 — INACTIVE 전환 + 30일 후 영구 삭제 예약, 쿠키 즉시 만료. */
    @DeleteMapping
    public ResponseEntity<WithdrawResponse> withdraw(@CurrentUserId String userId) {
        Instant purgeAt = withdrawService.requestWithdraw(userId);

        ResponseCookie expired = cookieFactory.expired();
        WithdrawResponse body = new WithdrawResponse(
                "회원 탈퇴 요청이 접수되었습니다. 30일 후 영구 삭제됩니다.",
                IsoTimestamp.format(purgeAt));

        log.info("회원 탈퇴 요청 처리 완료 (user_id={})", userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(body);
    }

    /** 탈퇴 취소 — 유예 기간 내 INACTIVE → ACTIVE 복구. */
    @PostMapping("/cancel")
    public MessageResponse cancelWithdraw(@CurrentUserId String userId) {
        withdrawService.cancelWithdraw(userId);
        log.info("회원 탈퇴 취소 처리 완료 (user_id={})", userId);
        return new MessageResponse("회원 탈퇴 요청이 취소되었습니다.");
    }
}
