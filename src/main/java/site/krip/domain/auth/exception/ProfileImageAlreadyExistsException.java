package site.krip.domain.auth.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 프로필 이미지가 이미 존재 (POST 시) — 409. 수정은 PUT 으로 유도.
 */
public class ProfileImageAlreadyExistsException extends ApiException {

    public ProfileImageAlreadyExistsException() {
        super(409, "이미 프로필 이미지가 존재합니다. 수정은 PUT 으로 요청해주세요.");
    }
}
