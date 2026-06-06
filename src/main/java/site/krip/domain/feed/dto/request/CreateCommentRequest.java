package site.krip.domain.feed.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import site.krip.domain.feed.entity.FeedPostComment;
import site.krip.global.common.validation.CodePointSize;

/**
 * 댓글 작성 요청. 공백만 입력은 서비스가 거절한다.
 */
public record CreateCommentRequest(
        @NotNull
        @Size(min = 1)
        @CodePointSize(max = FeedPostComment.COMMENT_MAX_LENGTH, message = "댓글은 최대 {max}자까지 가능합니다.")
        String content
) {
}
