package site.krip.domain.tripmate.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 게시글 수정/삭제/토글 권한 없음 — 403.
 */
public class PostAccessDeniedException extends ApiException {

    public PostAccessDeniedException(String message) {
        super(403, message);
    }
}
