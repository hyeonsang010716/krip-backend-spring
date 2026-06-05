package site.krip.global.share;

import site.krip.global.common.exception.ApiException;

/**
 * 공유 토큰이 무효하거나 만료됨 — 400.
 */
public class ShareTokenException extends ApiException {

    public ShareTokenException(String message) {
        super(400, message);
    }
}
