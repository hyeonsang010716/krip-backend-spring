package site.krip.domain.friend.dto.response;

import java.util.List;

/** 차단 목록 응답 (커서 페이지네이션). */
public record UserBlockListResponse(
        List<UserBlockResponse> items,
        String nextCursor
) {
}
