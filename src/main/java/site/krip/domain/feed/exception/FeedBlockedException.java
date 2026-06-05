package site.krip.domain.feed.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 양방향 차단 — 403.
 */
public class FeedBlockedException extends ApiException {

    public FeedBlockedException(String message) {
        super(403, message);
    }
}
