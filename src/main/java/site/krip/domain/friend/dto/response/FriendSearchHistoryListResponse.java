package site.krip.domain.friend.dto.response;

import java.util.List;

/** 친구 검색 기록 목록 (최신순). */
public record FriendSearchHistoryListResponse(List<FriendSearchHistoryResponse> histories) {
}
