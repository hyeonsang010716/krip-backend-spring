package site.krip.domain.feed.exception;

import site.krip.global.common.exception.ApiException;

/**
 * popup 대상 user 미존재 / 회원가입 미완료 — 404 (enumeration 차단).
 */
public class PopupTargetNotFoundException extends ApiException {

    public PopupTargetNotFoundException(String message) {
        super(404, message);
    }
}
