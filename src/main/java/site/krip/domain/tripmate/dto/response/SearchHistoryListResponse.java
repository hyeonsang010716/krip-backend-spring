package site.krip.domain.tripmate.dto.response;

import java.util.List;

/** 검색 기록 목록 (최신순). */
public record SearchHistoryListResponse(List<SearchHistoryResponse> histories) {
}
