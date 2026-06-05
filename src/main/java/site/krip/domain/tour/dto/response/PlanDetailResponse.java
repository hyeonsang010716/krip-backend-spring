package site.krip.domain.tour.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * 플랜 상세 응답 — 카드 및 소유자(userId) 포함.
 */
public record PlanDetailResponse(
        String planId,
        String userId,
        String title,
        int travelDays,
        Instant createdAt,
        Instant updatedAt,
        List<PlanItemResponse> items
) {
}
