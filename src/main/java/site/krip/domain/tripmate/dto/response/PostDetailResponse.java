package site.krip.domain.tripmate.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.tripmate.entity.CompanionType;
import site.krip.domain.tripmate.entity.PreferredGender;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 게시글 단건/목록 항목 응답.
 */
public record PostDetailResponse(
        String postId,
        String userId,
        AuthorResponse author,
        String title,
        String content,
        int preferredAgeMin,
        int preferredAgeMax,
        PreferredGender preferredGender,
        String region,
        LocalDate travelStartDate,
        LocalDate travelEndDate,
        CompanionType companionType,
        boolean isDisplayed,
        Instant createdAt,
        Instant updatedAt,
        long likeCount,
        boolean isLiked,
        List<String> imageUrls,
        @Nullable String profileImageUrl
) {
}
