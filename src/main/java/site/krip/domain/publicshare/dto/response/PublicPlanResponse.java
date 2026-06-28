package site.krip.domain.publicshare.dto.response;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 공개 share 응답의 plan 상세 — 소유자 식별(user_id)은 노출하지 않는다.
 */
public record PublicPlanResponse(
        String planId,
        @Nullable String title,
        int travelDays,
        Instant createdAt,
        Instant updatedAt,
        List<PublicPlanItemResponse> items
) {
}
