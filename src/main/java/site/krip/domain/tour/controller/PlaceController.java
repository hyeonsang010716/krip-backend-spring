package site.krip.domain.tour.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.tour.dto.request.FavoritePlaceRequest;
import site.krip.domain.tour.dto.response.FavoritePlaceListResponse;
import site.krip.domain.tour.dto.response.PlaceListResponse;
import site.krip.domain.tour.dto.response.PlaceResponse;
import site.krip.domain.tour.service.FavoritePlaceService;
import site.krip.domain.tour.service.PlaceService;
import site.krip.domain.tour.service.TourSearchHistoryService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

/**
 * 관광 장소 조회 + 즐겨찾기. 경로: {@code /api/tour/places}.
 */
@RestController
@RequestMapping("/api/tour/places")
@Validated
public class PlaceController {

    private static final Logger log = LoggerFactory.getLogger(PlaceController.class);

    // 기본 좌표 (서울 광화문)
    private static final double DEFAULT_LAT = 37.57594;
    private static final double DEFAULT_LNG = 126.97688;

    // 키워드 검색 기본 반경(m). 매칭 희소 키워드가 컬렉션 전체를 풀스캔하지 않게 $geoNear 순회를 주변으로 한정.
    // 클라이언트가 max_distance 를 주면 그 값이 우선. 일반 근처 조회엔 적용 안 함(unbounded 라도 저렴).
    private static final double KEYWORD_SEARCH_DEFAULT_DISTANCE_M = 15_000;

    private final PlaceService placeService;
    private final FavoritePlaceService favoritePlaceService;
    private final TourSearchHistoryService searchHistoryService;

    public PlaceController(PlaceService placeService, FavoritePlaceService favoritePlaceService,
                          TourSearchHistoryService searchHistoryService) {
        this.placeService = placeService;
        this.favoritePlaceService = favoritePlaceService;
        this.searchHistoryService = searchHistoryService;
    }

    // ──────────────────── 장소 조회 ────────────────────

    @GetMapping
    public PlaceListResponse getPlaces(@CurrentUserId String userId,
                                       @RequestParam(required = false)
                                       @DecimalMin(value = "-90", message = "위도는 -90 ~ 90 사이여야 합니다.")
                                       @DecimalMax(value = "90", message = "위도는 -90 ~ 90 사이여야 합니다.") Double lat,
                                       @RequestParam(required = false)
                                       @DecimalMin(value = "-180", message = "경도는 -180 ~ 180 사이여야 합니다.")
                                       @DecimalMax(value = "180", message = "경도는 -180 ~ 180 사이여야 합니다.") Double lng,
                                       @RequestParam(required = false)
                                       @Size(max = 100, message = "검색어는 100자 이하여야 합니다.") String keyword,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(name = "max_distance", required = false)
                                       @Positive(message = "max_distance 는 0 보다 커야 합니다.") Double maxDistance) {
        double actualLat = lat != null ? lat : DEFAULT_LAT;
        double actualLng = lng != null ? lng : DEFAULT_LNG;

        if (keyword != null && !keyword.isBlank()) {
            // 검색어 저장 (첫 페이지 요청 시에만, best-effort)
            if (cursor == null || cursor.isBlank()) {
                try {
                    searchHistoryService.saveSearch(userId, keyword);
                } catch (Exception e) {
                    log.warn("검색어 저장 실패 (무시)", e);
                }
            }
            double searchDistance = maxDistance != null ? maxDistance : KEYWORD_SEARCH_DEFAULT_DISTANCE_M;
            return placeService.searchNearbyPlaces(actualLat, actualLng, keyword, cursor, searchDistance, userId);
        }
        return placeService.getNearbyPlaces(actualLat, actualLng, cursor, maxDistance, userId);
    }

    // ──────────────────── 즐겨찾기 ────────────────────

    @GetMapping("/favorites")
    public FavoritePlaceListResponse getFavorites(@CurrentUserId String userId) {
        return favoritePlaceService.getFavorites(userId);
    }

    @PostMapping("/favorites")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse addFavorite(@CurrentUserId String userId,
                                       @Valid @RequestBody FavoritePlaceRequest body) {
        favoritePlaceService.addFavorite(userId, body.placeId());
        return new MessageResponse("즐겨찾기에 추가되었습니다.");
    }

    @DeleteMapping("/favorites/{place_id}")
    public MessageResponse removeFavorite(@CurrentUserId String userId, @PathVariable("place_id") String placeId) {
        favoritePlaceService.removeFavorite(userId, placeId);
        return new MessageResponse("즐겨찾기가 해제되었습니다.");
    }

    // ──────────────────── 장소 단건 조회 ────────────────────

    @GetMapping("/{place_id}")
    public PlaceResponse getPlace(@CurrentUserId String userId, @PathVariable("place_id") String placeId) {
        return placeService.getPlaceById(placeId, userId);
    }
}
