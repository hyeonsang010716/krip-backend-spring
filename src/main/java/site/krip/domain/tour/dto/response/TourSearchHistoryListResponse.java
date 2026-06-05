package site.krip.domain.tour.dto.response;

import java.util.List;

/** 검색 기록 목록 응답. */
public record TourSearchHistoryListResponse(List<TourSearchHistoryResponse> histories) {
}
