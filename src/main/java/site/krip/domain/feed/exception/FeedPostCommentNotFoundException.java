package site.krip.domain.feed.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 존재하지 않는 댓글 또는 post_id mismatch — 404 (enumeration 차단).
 */
public class FeedPostCommentNotFoundException extends ApiException {

    public FeedPostCommentNotFoundException(String message) {
        super(404, message);
    }
}
