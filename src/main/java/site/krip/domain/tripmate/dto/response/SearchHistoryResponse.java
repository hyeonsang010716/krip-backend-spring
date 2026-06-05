package site.krip.domain.tripmate.dto.response;

import java.time.Instant;

/** 검색 기록 단건. */
public record SearchHistoryResponse(String searchName, Instant createdAt) {
}
