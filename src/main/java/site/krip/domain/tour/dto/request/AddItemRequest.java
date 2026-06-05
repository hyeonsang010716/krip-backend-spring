package site.krip.domain.tour.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 카드 추가 요청 — 해당 day 의 맨 끝에 삽입.
 */
public record AddItemRequest(
        @NotNull @Min(1) Integer dayNumber,
        @NotNull @Size(min = 1, max = 255) String placeId,
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "visit_time 형식: HH:MM (24h)")
        String visitTime
) {
}
