package site.krip.domain.auth.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 탈퇴 요청 상태가 아닌 유저가 cancel 시도 — 409.
 */
public class WithdrawalNotPendingException extends ApiException {

    public WithdrawalNotPendingException(String currentStatus) {
        super(409, "탈퇴 요청 중인 유저가 아닙니다 (현재 status=" + currentStatus + ").");
    }
}
