package site.krip.domain.tour.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 카드 이동 요청.
 * after_item_id 다음 자리로 이동, null 이면 target day 의 맨 앞.
 */
public record MoveItemRequest(
        @NotNull @Min(1) Integer targetDayNumber,
        String afterItemId
) {
}
