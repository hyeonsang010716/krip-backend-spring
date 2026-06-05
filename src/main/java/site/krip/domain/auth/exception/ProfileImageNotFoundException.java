package site.krip.domain.auth.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 프로필 이미지가 존재하지 않음 (PUT/DELETE 시) — 404.
 */
public class ProfileImageNotFoundException extends ApiException {

    public ProfileImageNotFoundException(String message) {
        super(404, message);
    }
}
