package site.krip.domain.tour.dto.response;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** 장소 목록 응답 — 커서 페이지네이션. */
public record PlaceListResponse(
        List<PlaceResponse> places,
        @Nullable String nextCursor
) {
}
