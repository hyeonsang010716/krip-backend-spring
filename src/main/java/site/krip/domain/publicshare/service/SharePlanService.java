package site.krip.domain.publicshare.service;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.publicshare.dto.response.PublicPlanItemResponse;
import site.krip.domain.publicshare.dto.response.PublicPlanResponse;
import site.krip.domain.tour.document.Place;
import site.krip.domain.tour.entity.TourPlan;
import site.krip.domain.tour.entity.TourPlanItem;
import site.krip.domain.tour.exception.TourPlanNotFoundException;
import site.krip.domain.tour.repository.PlaceRepository;
import site.krip.domain.tour.repository.TourPlanRepository;
import site.krip.global.share.ShareTokenProvider;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공개 share 토큰으로 plan 조회.
 *
 * <p>인증/ownership 검증 없음 — 오로지 토큰 검증으로만 접근을 제어한다. TourPlanService 와
 * 책임을 분리(권한 모델이 다른 별개 도메인)하고, tour 의 plan/place 리포지토리만 재사용해
 * 응답을 빌드한다. 노출 응답에서 소유자 식별(user_id)은 제외.
 */
@Service
@RequiredArgsConstructor
public class SharePlanService {

    private final ShareTokenProvider shareTokenProvider;
    private final TourPlanRepository tourPlanRepository;
    private final PlaceRepository placeRepository;

    /**
     * 공유 토큰 디코드 → plan 조회 → 공개 응답 빌드.
     *
     * @throws site.krip.global.share.ShareTokenException 토큰 무효/만료 → 400
     * @throws TourPlanNotFoundException 디코드는 성공했으나 plan 이 없음 → 404
     */
    @Transactional(readOnly = true)
    public PublicPlanResponse getPlanByToken(String shareToken) {
        String planId = shareTokenProvider.decode(shareToken);  // 400 매핑

        TourPlan plan = tourPlanRepository.findByIdWithItems(planId)
                .orElseThrow(() -> new TourPlanNotFoundException("존재하지 않는 플랜입니다."));

        List<TourPlanItem> items = plan.getItems().stream()
                .sorted(Comparator.comparingInt(TourPlanItem::getDayNumber)
                        .thenComparingDouble(TourPlanItem::getPosition))
                .toList();

        Set<String> placeIds = items.stream()
                .map(TourPlanItem::getPlaceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Place> placeMap = placeRepository.findByPlaceIds(placeIds).stream()
                .collect(Collectors.toMap(Place::getPlaceId, Function.identity(), (a, b) -> a));

        List<PublicPlanItemResponse> itemResponses = items.stream()
                .map(i -> toItemResponse(i, placeMap.get(i.getPlaceId())))
                .toList();

        return new PublicPlanResponse(
                plan.getPlanId(),
                plan.getTitle(),
                plan.getTravelDays(),
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                itemResponses
        );
    }

    private static PublicPlanItemResponse toItemResponse(TourPlanItem item, @Nullable Place place) {
        Double rating = place != null ? place.getRating() : null;
        List<String> photos = place != null ? place.getPhotos() : List.of();
        return new PublicPlanItemResponse(
                item.getItemId(),
                item.getDayNumber(),
                item.getPosition(),
                item.getPlaceId(),
                item.getDisplayName(),
                item.getAddress(),
                item.getVisitTime(),
                rating,
                photos
        );
    }
}
