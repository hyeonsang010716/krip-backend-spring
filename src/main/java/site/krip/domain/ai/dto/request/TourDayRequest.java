package site.krip.domain.ai.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 일자별 여행 추천 요청. 의미 검증(cluster 존재·시간·enum)은 서비스에서 수행. */
public record TourDayRequest(
        @NotBlank(message = "departure_cluster 는 필수입니다.")
        String departureCluster,

        @NotBlank(message = "arrival_cluster 는 필수입니다.")
        String arrivalCluster,

        String additionalPlaceId,

        @NotBlank(message = "transport 는 필수입니다.")
        String transport,

        @NotBlank(message = "start_time 은 필수입니다.")
        String startTime,

        @NotBlank(message = "end_time 은 필수입니다.")
        String endTime,

        @NotBlank(message = "companion 은 필수입니다.")
        String companion,

        @Min(value = 0, message = "budget_per_person_krw 는 0 이상이어야 합니다.")
        int budgetPerPersonKrw,

        @NotEmpty(message = "styles 는 최소 1개 이상이어야 합니다.")
        @Size(max = 20, message = "styles 는 최대 20개까지 가능합니다.")
        List<String> styles,

        @NotBlank(message = "schedule_density 는 필수입니다.")
        String scheduleDensity
) {
}
