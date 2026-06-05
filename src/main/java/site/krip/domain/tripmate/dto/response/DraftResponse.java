package site.krip.domain.tripmate.dto.response;

import site.krip.domain.tripmate.document.TripmatePostDraft;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 임시저장 응답. 성별/동행타입은 자유 문자열.
 */
public record DraftResponse(
        String userId,
        String title,
        String content,
        Integer preferredAgeMin,
        Integer preferredAgeMax,
        String preferredGender,
        String region,
        LocalDate travelStartDate,
        LocalDate travelEndDate,
        String companionType,
        List<String> imageUrls,
        Instant updatedAt
) {
    public static DraftResponse from(TripmatePostDraft d) {
        return new DraftResponse(
                d.getUserId(), d.getTitle(), d.getContent(),
                d.getPreferredAgeMin(), d.getPreferredAgeMax(), d.getPreferredGender(),
                d.getRegion(), d.getTravelStartDate(), d.getTravelEndDate(), d.getCompanionType(),
                d.getImageUrls(), d.getUpdatedAt());
    }
}
