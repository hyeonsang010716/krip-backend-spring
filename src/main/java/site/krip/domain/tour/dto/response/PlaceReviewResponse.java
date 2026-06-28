package site.krip.domain.tour.dto.response;

import org.jspecify.annotations.Nullable;

/** 리뷰 응답. */
public record PlaceReviewResponse(
        String author,
        @Nullable Integer rating,
        @Nullable String relativeTime,
        @Nullable String text
) {
}
