package site.krip.domain.tripmate.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import site.krip.domain.tripmate.entity.CompanionType;
import site.krip.domain.tripmate.entity.PreferredGender;

import java.time.LocalDate;
import java.util.List;

/**
 * 게시글 생성 요청.
 */
public record CreatePostRequest(
        @NotBlank @Size(min = 1, max = 100) String title,
        @NotBlank @Size(min = 10, max = 500) String content,
        @NotNull @Min(1) Integer preferredAgeMin,
        @NotNull @Min(1) Integer preferredAgeMax,
        @NotNull PreferredGender preferredGender,
        @NotBlank @Size(max = 100) String region,
        @NotNull LocalDate travelStartDate,
        @NotNull LocalDate travelEndDate,
        @NotNull CompanionType companionType,
        List<String> imageUrls
) {
    @AssertTrue(message = "선호 나이 최소값은 최대값보다 클 수 없습니다.")
    public boolean isPreferredAgeRangeValid() {
        return preferredAgeMin == null || preferredAgeMax == null || preferredAgeMin <= preferredAgeMax;
    }

    @AssertTrue(message = "여행 종료일은 시작일보다 빠를 수 없습니다.")
    public boolean isTravelDateRangeValid() {
        return travelStartDate == null || travelEndDate == null || !travelEndDate.isBefore(travelStartDate);
    }
}
