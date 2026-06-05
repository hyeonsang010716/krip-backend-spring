package site.krip.domain.tour.dto.response;

import java.util.List;

/** 즐겨찾기 목록 응답. */
public record FavoritePlaceListResponse(
        List<FavoritePlaceResponse> favorites,
        int totalCount
) {
}
