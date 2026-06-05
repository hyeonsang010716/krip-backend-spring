package site.krip.domain.friend.dto.response;

import java.util.List;

/** 친구 검색 목록 응답 (커서 페이지네이션). */
public record FriendSearchListResponse(
        List<FriendSearchItemResponse> items,
        String nextCursor
) {
}
