package site.krip.domain.friend.dto.response;

import java.time.Instant;

/** 친구 검색 기록 단건. */
public record FriendSearchHistoryResponse(String searchName, Instant createdAt) {
}
