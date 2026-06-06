package site.krip.domain.tour.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.tour.document.Place;
import site.krip.domain.tour.dto.response.FavoritePlaceListResponse;
import site.krip.domain.tour.dto.response.FavoritePlaceResponse;
import site.krip.domain.tour.dto.response.PlaceDetailResponse;
import site.krip.domain.tour.entity.FavoritePlace;
import site.krip.domain.tour.repository.FavoritePlaceRepository;
import site.krip.domain.tour.repository.PlaceRepository;
import site.krip.global.common.exception.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 즐겨찾기.
 *
 * <p>RDB(favorite_place) + MongoDB(place) 결합. 추가 시 장소 존재 검증 후 중복 차단,
 * 목록은 즐겨찾기 순서(최신순)를 유지하며 Place 상세를 병합(사라진 장소는 skip).
 */
@Service
public class FavoritePlaceService {

    private final FavoritePlaceRepository favRepo;
    private final PlaceRepository placeRepo;

    public FavoritePlaceService(FavoritePlaceRepository favRepo, PlaceRepository placeRepo) {
        this.favRepo = favRepo;
        this.placeRepo = placeRepo;
    }

    @Transactional
    public void addFavorite(String userId, String placeId) {
        if (placeRepo.findByPlaceIds(List.of(placeId)).isEmpty()) {
            throw ApiException.badRequest("존재하지 않는 장소입니다.");
        }
        if (favRepo.existsByUserIdAndPlaceId(userId, placeId)) {
            throw ApiException.badRequest("이미 즐겨찾기한 장소입니다.");
        }
        favRepo.save(new FavoritePlace(userId, placeId));
    }

    @Transactional
    public void removeFavorite(String userId, String placeId) {
        if (!favRepo.existsByUserIdAndPlaceId(userId, placeId)) {
            throw ApiException.badRequest("즐겨찾기하지 않은 장소입니다.");
        }
        favRepo.deleteByUserIdAndPlaceId(userId, placeId);
    }

    @Transactional(readOnly = true)
    public FavoritePlaceListResponse getFavorites(String userId) {
        List<FavoritePlace> favorites = favRepo.findAllByUserOrderByCreatedAtDesc(userId);
        if (favorites.isEmpty()) {
            return new FavoritePlaceListResponse(List.of(), 0);
        }

        List<String> placeIds = favorites.stream().map(FavoritePlace::getPlaceId).toList();
        Map<String, Place> placeMap = placeRepo.findByPlaceIds(placeIds).stream()
                .collect(Collectors.toMap(Place::getPlaceId, Function.identity(), (a, b) -> a));

        // 즐겨찾기 순서(최신순) 유지하며 장소 상세 병합, 사라진 장소는 skip
        List<FavoritePlaceResponse> result = new ArrayList<>();
        for (FavoritePlace fav : favorites) {
            Place place = placeMap.get(fav.getPlaceId());
            if (place == null) {
                continue;
            }
            result.add(new FavoritePlaceResponse(
                    fav.getFavoriteId(),
                    fav.getCreatedAt(),
                    PlaceDetailResponse.from(place)));
        }
        return new FavoritePlaceListResponse(result, result.size());
    }
}
