package site.krip.domain.tripmate.dto.request;

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
}
