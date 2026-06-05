package site.krip.domain.feed.dto.response;

import java.util.List;

/** 댓글 목록 응답 — 커서 페이지네이션, 최신순. */
public record CommentListResponse(
        List<CommentResponse> comments,
        String nextCursor
) {
}
