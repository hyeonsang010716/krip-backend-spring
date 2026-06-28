package site.krip.domain.tour.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.tour.entity.TourPlan;

import java.time.Instant;

/**
 * 플랜 목록 항목 응답 — 메타만.
 */
public record PlanSummaryResponse(
        String planId,
        @Nullable String title,
        int travelDays,
        Instant createdAt,
        Instant updatedAt
) {
    public static PlanSummaryResponse from(TourPlan plan) {
        return new PlanSummaryResponse(
                plan.getPlanId(), plan.getTitle(), plan.getTravelDays(),
                plan.getCreatedAt(), plan.getUpdatedAt());
    }
}
