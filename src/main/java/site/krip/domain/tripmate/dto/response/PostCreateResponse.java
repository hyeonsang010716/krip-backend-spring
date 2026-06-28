package site.krip.domain.tripmate.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.tripmate.entity.CompanionType;
import site.krip.domain.tripmate.entity.PreferredGender;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 게시글 생성 응답 — 작성자 본인이라 author/like 불필요.
 */
public record PostCreateResponse(
        String postId,
        String userId,
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
        List<String> imageUrls,
        @Nullable String profileImageUrl
) {
}
