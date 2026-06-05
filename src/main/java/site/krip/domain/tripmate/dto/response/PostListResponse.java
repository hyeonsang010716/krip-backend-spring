package site.krip.domain.tripmate.dto.response;

import java.util.List;

/**
 * 게시글 목록 응답 (커서 페이지네이션).
 */
public record PostListResponse(
        List<PostDetailResponse> posts,
        String nextCursor
) {
}
