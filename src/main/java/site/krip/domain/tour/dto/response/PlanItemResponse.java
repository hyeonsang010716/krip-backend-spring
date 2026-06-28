package site.krip.domain.tour.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.tour.entity.TourPlanItem;

import java.util.List;

/**
 * 카드 단건 응답. rating/photos 는 라이브 값.
 */
public record PlanItemResponse(
        String itemId,
        int dayNumber,
        double position,
        String placeId,
        String displayName,
        String address,
        @Nullable String visitTime,
        @Nullable Double rating,
        List<String> photos
) {
    public static PlanItemResponse of(TourPlanItem item, @Nullable Double rating, List<String> photos) {
        return new PlanItemResponse(
                item.getItemId(), item.getDayNumber(), item.getPosition(), item.getPlaceId(),
                item.getDisplayName(), item.getAddress(), item.getVisitTime(),
                rating, photos != null ? photos : List.of());
    }
}
