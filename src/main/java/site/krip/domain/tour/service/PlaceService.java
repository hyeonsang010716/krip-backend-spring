package site.krip.domain.tour.service;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.tour.document.Place;
import site.krip.domain.tour.dto.response.PlaceListResponse;
import site.krip.domain.tour.dto.response.PlaceResponse;
import site.krip.domain.tour.repository.FavoritePlaceRepository;
import site.krip.domain.tour.repository.PlaceRepository;
import site.krip.domain.tour.repository.PlaceRepository.NearbyPlace;
import site.krip.global.common.exception.ApiException;

import java.util.List;
import java.util.Set;

/**
 * 장소 조회.
 *
 * <p>거리순/키워드 조회는 MongoDB $geoNear, 로그인 유저의 즐겨찾기 여부는 RDB 배치 조회로 병합.
 * is_favorite 는 즐겨찾기 O 면 true, X 면 null.
 */
@Service
public class PlaceService {

    private final PlaceRepository placeRepo;
    private final FavoritePlaceRepository favRepo;

    public PlaceService(PlaceRepository placeRepo, FavoritePlaceRepository favRepo) {
        this.placeRepo = placeRepo;
        this.favRepo = favRepo;
    }

    @Transactional(readOnly = true)
    public PlaceListResponse getNearbyPlaces(double lat, double lng, String cursor,
                                             Double maxDistance, String userId) {
        return toListResponse(placeRepo.findNearby(lat, lng, cursor, maxDistance), userId);
    }

    @Transactional(readOnly = true)
    public PlaceListResponse searchNearbyPlaces(double lat, double lng, String keyword,
                                                String cursor, Double maxDistance, String userId) {
        return toListResponse(placeRepo.searchNearby(lat, lng, keyword, cursor, maxDistance), userId);
    }

    /** place_id 단건 조회 — 없으면 404. 거리는 제공 안 되므로 0. */
    @Transactional(readOnly = true)
    public PlaceResponse getPlaceById(String placeId, String userId) {
        Place place = placeRepo.findByPlaceId(placeId)
                .orElseThrow(() -> ApiException.notFound("장소를 찾을 수 없습니다."));
        Set<String> favorited = favoritedSet(List.of(place.getPlaceId()), userId);
        return PlaceResponse.of(place, 0.0, isFavorite(favorited, place.getPlaceId()));
    }

    private PlaceListResponse toListResponse(List<NearbyPlace> fetched, String userId) {
        // PAGE_SIZE+1 조회 → 초과분으로 hasMore 판정(마지막 꽉 찬 페이지의 phantom 커서 방지).
        boolean hasMore = fetched.size() > PlaceRepository.PAGE_SIZE;
        List<NearbyPlace> page = hasMore ? fetched.subList(0, PlaceRepository.PAGE_SIZE) : fetched;

        List<String> placeIds = page.stream().map(n -> n.place().getPlaceId()).toList();
        Set<String> favorited = favoritedSet(placeIds, userId);

        List<PlaceResponse> places = page.stream()
                .map(n -> PlaceResponse.of(n.place(), n.distance(),
                        isFavorite(favorited, n.place().getPlaceId())))
                .toList();

        String nextCursor = null;
        if (hasMore) {
            NearbyPlace last = page.get(page.size() - 1);
            nextCursor = PlaceRepository.buildCursor(last.distance(), last.place().getPlaceId());
        }
        return new PlaceListResponse(places, nextCursor);
    }

    /** 유저의 즐겨찾기 place_id set (비로그인/빈 목록이면 빈 set). */
    private Set<String> favoritedSet(List<String> placeIds, String userId) {
        if (userId == null || userId.isBlank() || placeIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(favRepo.findFavoritedPlaceIds(userId, placeIds));
    }

    private static @Nullable Boolean isFavorite(Set<String> favorited, String placeId) {
        return favorited.contains(placeId) ? Boolean.TRUE : null;
    }
}
