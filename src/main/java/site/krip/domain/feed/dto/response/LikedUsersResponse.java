package site.krip.domain.feed.dto.response;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** 좋아요 누른 유저 목록 — 최신순, 커서 페이지네이션. */
public record LikedUsersResponse(String postId, List<LikedUserItem> users, @Nullable String nextCursor) {
}
