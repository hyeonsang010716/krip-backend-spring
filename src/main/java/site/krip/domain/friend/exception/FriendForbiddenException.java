package site.krip.domain.friend.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 친구 요청 수락/거절/취소/삭제 권한 없음 — 403.
 */
public class FriendForbiddenException extends ApiException {

    public FriendForbiddenException(String message) {
        super(403, message);
    }
}
