package site.krip.domain.tour.dto.response;

/** 리뷰 응답. */
public record PlaceReviewResponse(
        String author,
        Integer rating,
        String relativeTime,
        String text
) {
}
