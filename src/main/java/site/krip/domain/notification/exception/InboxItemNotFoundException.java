package site.krip.domain.notification.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 인박스 항목 미존재/타인 소유/이미 hide/잘못된 id 형식 — 404.
 */
public class InboxItemNotFoundException extends ApiException {

    public InboxItemNotFoundException(String message) {
        super(404, message);
    }
}
