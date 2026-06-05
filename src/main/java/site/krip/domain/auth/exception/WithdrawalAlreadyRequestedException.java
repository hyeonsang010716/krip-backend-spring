package site.krip.domain.auth.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 이미 탈퇴 요청이 진행 중 (유예 기간 내 재요청) — 409.
 */
public class WithdrawalAlreadyRequestedException extends ApiException {

    public WithdrawalAlreadyRequestedException() {
        super(409, "이미 탈퇴가 요청된 유저입니다.");
    }
}
