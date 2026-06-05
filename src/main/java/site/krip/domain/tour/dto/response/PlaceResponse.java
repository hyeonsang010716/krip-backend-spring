package site.krip.domain.tour.dto.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import site.krip.domain.tour.document.Place;

/**
 * 장소 조회 응답 — 상세 + 거리 + 즐겨찾기. 목록 경로에서만 distance/isFavorite 채움.
 *
 * <p>{@code @JsonUnwrapped} 로 상세 필드를 최상위로 펼친다. isFavorite 는 즐겨찾기 아니면 null, 맞으면 true.
 */
public record PlaceResponse(
        @JsonUnwrapped PlaceDetailResponse detail,
        double distance,
        Boolean isFavorite
) {
    public static PlaceResponse of(Place place, double distance, Boolean isFavorite) {
        return new PlaceResponse(PlaceDetailResponse.from(place), distance, isFavorite);
    }
}
