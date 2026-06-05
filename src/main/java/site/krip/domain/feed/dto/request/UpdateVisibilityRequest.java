package site.krip.domain.feed.dto.request;

import jakarta.validation.constraints.NotNull;
import site.krip.domain.feed.entity.FeedVisibility;

/** 공개 범위 변경 요청. */
public record UpdateVisibilityRequest(
        @NotNull FeedVisibility visibility
) {
}
