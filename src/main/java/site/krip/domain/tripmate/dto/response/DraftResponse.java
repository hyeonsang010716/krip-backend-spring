package site.krip.domain.tripmate.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.tripmate.document.TripmatePostDraft;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 임시저장 응답. 성별/동행타입은 자유 문자열. draft 는 미완성 저장이라 대부분 필드 nullable.
 */
public record DraftResponse(
        String userId,
        @Nullable String title,
        @Nullable String content,
        @Nullable Integer preferredAgeMin,
        @Nullable Integer preferredAgeMax,
        @Nullable String preferredGender,
        @Nullable String region,
        @Nullable LocalDate travelStartDate,
        @Nullable LocalDate travelEndDate,
        @Nullable String companionType,
        List<String> imageUrls,
        @Nullable Instant updatedAt
) {
    public static DraftResponse from(TripmatePostDraft d) {
        return new DraftResponse(
                d.getUserId(), d.getTitle(), d.getContent(),
                d.getPreferredAgeMin(), d.getPreferredAgeMax(), d.getPreferredGender(),
                d.getRegion(), d.getTravelStartDate(), d.getTravelEndDate(), d.getCompanionType(),
                d.getImageUrls(), d.getUpdatedAt());
    }
}
