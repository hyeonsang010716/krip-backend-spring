package site.krip.domain.friend.dto.response;

import java.util.List;

/** 친구/요청 목록 응답 (커서 페이지네이션). */
public record FriendshipListResponse(
        List<FriendshipResponse> items,
        String nextCursor
) {
}
