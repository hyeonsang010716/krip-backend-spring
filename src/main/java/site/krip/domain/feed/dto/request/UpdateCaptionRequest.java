package site.krip.domain.feed.dto.request;

import site.krip.domain.feed.entity.FeedPost;
import site.krip.global.common.validation.CodePointSize;

/**
 * 캡션 변경 요청. null/빈/공백만 입력은 캡션 삭제로 처리한다(서비스가 정규화).
 */
public record UpdateCaptionRequest(
        @CodePointSize(max = FeedPost.CAPTION_MAX_LENGTH, message = "캡션은 최대 {max}자까지 가능합니다.")
        String caption
) {
}
