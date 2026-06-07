package site.krip.domain.tour.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 플랜 + 카드 일괄 생성 요청.
 *
 * <p>title 공백 정규화/거부는 서비스에서 처리(공백만 → 400). visit_time 은 HH:MM(24h).
 * travelDays/items 에 상한을 둬 비현실적 거대 플랜 생성을 차단한다.
 */
public record CreatePlanRequest(
        @Size(max = 100) String title,
        @NotNull @Min(1) @Max(365) Integer travelDays,
        @NotEmpty @Size(max = 500) @Valid List<Item> items
) {
    public record Item(
            @NotNull @Min(1) Integer dayNumber,
            @NotNull @Size(min = 1, max = 255) String placeId,
            @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "visit_time 형식: HH:MM (24h)")
            String visitTime
    ) {
    }
}
