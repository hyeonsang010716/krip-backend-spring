package site.krip.domain.tour.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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
                                       @RequestParam(required = false) Double lat,
                                       @RequestParam(required = false) Double lng,
                                       @RequestParam(required = false) String keyword,
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
            return placeService.searchNearbyPlaces(actualLat, actualLng, keyword, cursor, maxDistance, userId);
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
