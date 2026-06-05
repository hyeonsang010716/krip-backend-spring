package site.krip.domain.feed.dto.response;

import java.util.List;

/** 피드 목록 응답 — 커서 페이지네이션. */
public record FeedPostListResponse(
        List<FeedPostResponse> posts,
        String nextCursor
) {
}
