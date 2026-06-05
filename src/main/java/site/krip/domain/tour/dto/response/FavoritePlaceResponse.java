package site.krip.domain.tour.dto.response;

import java.time.Instant;

/**
 * 즐겨찾기 단건 응답.
 * createdAt 은 Jackson(JavaTimeModule)이 ISO-8601 로 직렬화.
 */
public record FavoritePlaceResponse(
        String favoriteId,
        Instant createdAt,
        PlaceDetailResponse place
) {
}
