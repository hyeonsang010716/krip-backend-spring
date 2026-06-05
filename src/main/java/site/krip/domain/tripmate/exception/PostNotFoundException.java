package site.krip.domain.tripmate.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 존재하지 않는 게시글 — 조회/수정/삭제/토글/좋아요목록에서 404.
 * 좋아요 추가/취소는 400 이므로 그쪽은 인라인 처리.
 */
public class PostNotFoundException extends ApiException {

    public PostNotFoundException() {
        super(404, "존재하지 않는 게시글입니다.");
    }
}
