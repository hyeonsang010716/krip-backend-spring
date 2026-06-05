package site.krip.domain.tour.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 즐겨찾기 추가 요청.
 */
public record FavoritePlaceRequest(
        @NotBlank String placeId
) {
}
