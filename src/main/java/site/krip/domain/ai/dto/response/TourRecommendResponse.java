package site.krip.domain.ai.dto.response;

import java.util.List;

/**
 * 여행 추천 응답 — FastAPI {@code TourRecommendResponse} 와 동일 형태(그대로 클라이언트에 전달).
 */
public record TourRecommendResponse(List<TourDay> tourPlan) {

    public record TourDay(
            int day,
            List<TimelineSlot> timeline,
            List<PlaceDetail> places,
            List<MovementHop> movements,
            List<BudgetItem> budgetBreakdown,
            long budgetTotalKrw,
            String summary
    ) {
    }

    public record TimelineSlot(String time, String placeId, String title) {
    }

    public record PlaceDetail(
            String placeId,
            String displayName,
            String category,
            String address,
            Location location,
            Double rating,
            String reason,
            long estimatedCostKrw,
            int stayMinutes,
            List<String> photos
    ) {
    }

    public record Location(double lat, double lng) {
    }

    public record MovementHop(String fromPlace, String toPlace, String method) {
    }

    public record BudgetItem(String label, long amountKrw) {
    }
}
