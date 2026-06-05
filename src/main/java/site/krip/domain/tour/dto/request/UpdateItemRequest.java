package site.krip.domain.tour.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 카드 교체(PUT) 요청.
 * place_id 변경 시 display_name/address 스냅샷도 갱신. visit_time=null → 시각 미정.
 *
 * <p>PUT 의미상 {@code visit_time} 키는 body 에 반드시 있어야 한다({@code required=true}, 값 null 허용).
 * place_id 는 {@code @NotNull} 로 누락 시 거부.
 */
public record UpdateItemRequest(
        @NotNull @Size(min = 1, max = 255) String placeId,
        @JsonProperty(value = "visit_time", required = true)
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "visit_time 형식: HH:MM (24h)")
        String visitTime
) {
}
