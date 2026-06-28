package site.krip.domain.feed.dto.response;

import org.jspecify.annotations.Nullable;
import site.krip.domain.auth.entity.TravelStyle;

import java.util.List;

/**
 * 피드 팝업 응답 — 프로필 5종 + 최근 9개 피드.
 */
public record FeedPopupResponse(
        String userId,
        String userName,
        String nationality,
        List<TravelStyle> travelStyles,
        @Nullable String profileImageUrl,
        PopupFeedSection feed
) {
    /** 팝업 피드 영역 — 최근 N개(default 9), next_cursor 미제공. */
    public record PopupFeedSection(List<FeedPostResponse> items) {
    }
}
