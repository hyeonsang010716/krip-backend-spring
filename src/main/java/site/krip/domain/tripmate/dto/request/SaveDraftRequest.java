package site.krip.domain.tripmate.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 게시글 임시저장 요청. 모든 필드 선택.
 * 성별/동행타입은 임시저장 단계라 자유 문자열로 둔다.
 */
public record SaveDraftRequest(
        @Size(max = 100) String title,
        @Size(max = 500) String content,
        @Min(1) Integer preferredAgeMin,
        @Min(1) Integer preferredAgeMax,
        String preferredGender,
        @Size(max = 100) String region,
        LocalDate travelStartDate,
        LocalDate travelEndDate,
        String companionType,
        List<String> imageUrls
) {
}
