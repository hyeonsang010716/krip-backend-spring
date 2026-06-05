package site.krip.domain.auth.exception;

import site.krip.global.common.exception.ApiException;

/** 존재하지 않는 유저 — 404. */
public class UserNotFoundException extends ApiException {

    public UserNotFoundException() {
        super(404, "존재하지 않는 유저입니다.");
    }
}
