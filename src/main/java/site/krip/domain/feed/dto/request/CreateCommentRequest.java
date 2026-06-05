package site.krip.domain.feed.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 댓글 작성 요청. 공백만 입력은 서비스가 거절한다.
 *
 * <p>최대 길이는 컨트롤러가 코드포인트 기준으로 검사한다.
 * {@code @Size(max)} 는 UTF-16 코드유닛이라 이모지를 과도 거부하므로 min 만 둔다.
 */
public record CreateCommentRequest(
        @NotNull @Size(min = 1) String content
) {
}
