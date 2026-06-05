package site.krip.domain.auth.dto.response;

/** 마이페이지 통계 — 본인 피드 좋아요 합 + ACCEPTED 친구 수 (응답 시점 스냅샷). */
public record ProfileStatsResponse(
        long totalFeedLikes,
        long totalFriends
) {
}
