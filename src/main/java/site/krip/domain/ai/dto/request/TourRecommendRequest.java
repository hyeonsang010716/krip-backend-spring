package site.krip.domain.ai.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 전체 여행 추천 요청. {@code days} 길이 == {@code travelDays} 검증은 서비스에서 수행. */
public record TourRecommendRequest(
        @Min(value = 1, message = "travel_days 는 1 이상이어야 합니다.")
        @Max(value = 3, message = "travel_days 는 3 이하여야 합니다.")
        int travelDays,

        @NotBlank(message = "food_preference 는 필수입니다.")
        @Pattern(regexp = "halal|vegetarian|any", message = "food_preference 는 halal|vegetarian|any 중 하나여야 합니다.")
        String foodPreference,

        @NotEmpty(message = "days 는 필수입니다.")
        @Size(max = 3, message = "days 는 최대 3개까지 가능합니다.")
        @Valid
        List<TourDayRequest> days
) {
}
