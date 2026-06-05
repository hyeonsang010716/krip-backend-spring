package site.krip.domain.tour.dto.response;

import java.util.List;

/** 플랜 목록 응답. */
public record PlanListResponse(List<PlanSummaryResponse> plans) {
}
