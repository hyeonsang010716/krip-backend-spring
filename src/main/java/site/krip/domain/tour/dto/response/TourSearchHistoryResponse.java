package site.krip.domain.tour.dto.response;

import site.krip.domain.tour.document.TourSearchHistory;

import java.time.Instant;

/** 검색 기록 단건 응답. */
public record TourSearchHistoryResponse(
        String searchName,
        Instant createdAt
) {
    public static TourSearchHistoryResponse from(TourSearchHistory h) {
        return new TourSearchHistoryResponse(h.getSearchName(), h.getCreatedAt());
    }
}
