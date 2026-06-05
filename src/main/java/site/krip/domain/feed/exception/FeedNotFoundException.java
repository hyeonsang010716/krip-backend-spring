package site.krip.domain.feed.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 존재하지 않는 게시물 — 404.
 * visibility 미충족도 본 예외로 일원화(정보 누출 회피).
 */
public class FeedNotFoundException extends ApiException {

    public FeedNotFoundException(String message) {
        super(404, message);
    }
}
