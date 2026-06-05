package site.krip.domain.auth.exception;

import site.krip.global.common.exception.ApiException;

/** 2차 회원가입 미완료 — 400. 필터가 통상 먼저 차단하지만 서비스 레벨에서도 방어. */
public class ProfileNotRegisteredException extends ApiException {

    public ProfileNotRegisteredException() {
        super(400, "2차 회원가입이 완료되지 않은 유저입니다.");
    }
}
