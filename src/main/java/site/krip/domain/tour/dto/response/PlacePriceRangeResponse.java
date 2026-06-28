package site.krip.domain.tour.dto.response;

import org.jspecify.annotations.Nullable;

/** 가격 범위 응답. */
public record PlacePriceRangeResponse(@Nullable String min, @Nullable String max) {
}
