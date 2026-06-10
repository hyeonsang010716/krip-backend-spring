package site.krip.domain.tour.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.tour.document.Place;
import site.krip.domain.tour.dto.request.CreatePlanRequest;
import site.krip.domain.tour.dto.response.PlanDetailResponse;
import site.krip.domain.tour.dto.response.PlanItemResponse;
import site.krip.domain.tour.dto.response.PlanListResponse;
import site.krip.domain.tour.dto.response.PlanSummaryResponse;
import site.krip.domain.tour.dto.response.ShareTokenResponse;
import site.krip.domain.tour.entity.TourPlan;
import site.krip.domain.tour.entity.TourPlanItem;
import site.krip.domain.tour.exception.TourPlanItemNotFoundException;
import site.krip.domain.tour.exception.TourPlanNotFoundException;
import site.krip.domain.tour.repository.PlaceRepository;
import site.krip.domain.tour.repository.TourPlanItemRepository;
import site.krip.domain.tour.repository.TourPlanRepository;
import site.krip.global.common.exception.ApiException;
import site.krip.global.share.ShareTokenProvider;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 여행 플랜 CRUD + 카드 편집.
 *
 * <p>카드 추가/이동은 UNIQUE(plan_id, day_number, position) 경합을 만날 수 있어, 매 시도마다
 * position 을 재계산하며 새 트랜잭션에서 재시도한다.
 * 카드 변경은 plan row 를 안 건드리므로 {@code plan.touch()} 로 updated_at 을 명시적으로 올린다.
 */
@Service
public class TourPlanService {

    /** 카드 position 기본 간격 — 클수록 같은 자리 반복 삽입 시 float 정밀도 여유 ↑. */
    private static final double POSITION_SPACING = 1024.0;
    /** UNIQUE(plan,day,position) 경합 시 최대 재시도 횟수. */
    private static final int MAX_POSITION_RETRY = 3;

    private final TourPlanRepository planRepo;
    private final TourPlanItemRepository itemRepo;
    private final PlaceRepository placeRepo;
    private final ShareTokenProvider shareTokenProvider;
    private final TransactionTemplate txTemplate;

    public TourPlanService(TourPlanRepository planRepo, TourPlanItemRepository itemRepo,
                           PlaceRepository placeRepo, ShareTokenProvider shareTokenProvider,
                           TransactionTemplate txTemplate) {
        this.planRepo = planRepo;
        this.itemRepo = itemRepo;
        this.placeRepo = placeRepo;
        this.shareTokenProvider = shareTokenProvider;
        this.txTemplate = txTemplate;
    }

    // ──────────────────── 플랜 생성 ────────────────────

    public PlanDetailResponse createPlan(String userId, CreatePlanRequest body) {
        int travelDays = body.travelDays();
        List<CreatePlanRequest.Item> items = body.items();
        for (CreatePlanRequest.Item it : items) {
            if (it.dayNumber() > travelDays) {
                throw ApiException.badRequest("day_number 가 범위를 벗어났습니다: " + it.dayNumber());
            }
        }

        // MongoDB 배치 조회 → place_id 스냅샷 (RDB 트랜잭션 밖 — 커넥션 미점유)
        Set<String> placeIds = items.stream()
                .map(CreatePlanRequest.Item::placeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Place> placeMap = placeRepo.findByPlaceIds(placeIds).stream()
                .collect(Collectors.toMap(Place::getPlaceId, Function.identity(), (a, b) -> a));
        List<String> missing = placeIds.stream().filter(id -> !placeMap.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("존재하지 않는 장소가 있습니다: " + missing);
        }

        // planId 는 생성자에서 부여되므로 트랜잭션 전에 카드 엔티티를 미리 구성.
        TourPlan plan = new TourPlan(userId, normalizeTitle(body.title()), travelDays);
        // day 별 position 을 SPACING 간격으로 부여 (같은 day 안에서는 시퀀셜이라 race 무관)
        Map<Integer, Double> dayPositions = new LinkedHashMap<>();
        List<TourPlanItem> entities = items.stream().map(it -> {
            double pos = dayPositions.merge(it.dayNumber(), POSITION_SPACING, Double::sum);
            Place raw = placeMap.get(it.placeId());
            return new TourPlanItem(plan.getPlanId(), it.dayNumber(), pos, it.placeId(),
                    raw.getDisplayName(), raw.getAddress(), it.visitTime());
        }).toList();

        // RDB 쓰기만 트랜잭션 — plan 을 먼저 flush 해 item 의 plan_id 참조 순서를 보장.
        txTemplate.executeWithoutResult(s -> {
            planRepo.saveAndFlush(plan);
            itemRepo.saveAll(entities);
        });

        List<TourPlanItem> sorted = entities.stream()
                .sorted(Comparator.comparingInt(TourPlanItem::getDayNumber)
                        .thenComparingDouble(TourPlanItem::getPosition))
                .toList();
        return toDetailResponse(plan, sorted, placeMap);
    }

    // ──────────────────── 플랜 조회 ────────────────────

    @Transactional(readOnly = true)
    public PlanDetailResponse getPlan(String planId, String userId) {
        TourPlan plan = planRepo.findByIdWithItems(planId)
                .orElseThrow(() -> new TourPlanNotFoundException("존재하지 않는 플랜입니다."));
        if (!plan.getUserId().equals(userId)) {
            throw ApiException.forbidden("플랜 조회 권한이 없습니다.");
        }

        List<TourPlanItem> items = plan.getItems().stream()
                .sorted(Comparator.comparingInt(TourPlanItem::getDayNumber)
                        .thenComparingDouble(TourPlanItem::getPosition))
                .toList();
        Set<String> placeIds = items.stream()
                .map(TourPlanItem::getPlaceId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Place> placeMap = placeRepo.findByPlaceIds(placeIds).stream()
                .collect(Collectors.toMap(Place::getPlaceId, Function.identity(), (a, b) -> a));
        return toDetailResponse(plan, items, placeMap);
    }

    @Transactional(readOnly = true)
    public PlanListResponse getPlans(String userId) {
        List<PlanSummaryResponse> plans = planRepo.findAllByUserId(userId).stream()
                .map(PlanSummaryResponse::from)
                .toList();
        return new PlanListResponse(plans);
    }

    // ──────────────────── 플랜 메타 수정 ────────────────────

    @Transactional
    public PlanSummaryResponse updatePlanTitle(String planId, String userId, String title) {
        TourPlan plan = findOwnedPlan(planId, userId, "플랜 수정 권한이 없습니다.");
        plan.changeTitle(normalizeTitle(title));
        planRepo.flush();
        return PlanSummaryResponse.from(plan);
    }

    // ──────────────────── 공유 토큰 발급 ────────────────────

    @Transactional(readOnly = true)
    public ShareTokenResponse generateShareToken(String planId, String userId) {
        findOwnedPlan(planId, userId, "플랜 공유 권한이 없습니다.");
        ShareTokenProvider.Issued issued = shareTokenProvider.encode(planId);
        return new ShareTokenResponse(issued.token(), issued.expiresAt());
    }

    // ──────────────────── 일차 추가/삭제 ────────────────────

    @Transactional
    public PlanSummaryResponse addDay(String planId, String userId) {
        TourPlan plan = findOwnedPlan(planId, userId, "플랜 수정 권한이 없습니다.");
        plan.addDay();
        planRepo.flush();
        return PlanSummaryResponse.from(plan);
    }

    @Transactional
    public void removeDay(String planId, String userId, int dayNumber) {
        TourPlan plan = findOwnedPlan(planId, userId, "플랜 수정 권한이 없습니다.");
        if (dayNumber < 1 || dayNumber > plan.getTravelDays()) {
            throw ApiException.badRequest("day_number 가 범위를 벗어났습니다: " + dayNumber);
        }
        // clearAutomatically=true 인 벌크 삭제가 PC 를 비워 plan 을 detach 시키므로,
        // updated_at touch 는 삭제보다 먼저 flush 해 UPDATE 유실을 막는다(삭제 후엔 detached 라 더티체킹 안 됨).
        plan.touch();
        planRepo.flush();
        itemRepo.deleteByPlanIdAndDayNumber(planId, dayNumber);
    }

    @Transactional
    public void deletePlan(String planId, String userId) {
        TourPlan plan = findOwnedPlan(planId, userId, "플랜 삭제 권한이 없습니다.");
        planRepo.delete(plan);
    }

    // ──────────────────── 카드 추가 (position 경합 재시도) ────────────────────

    public PlanItemResponse addItem(String planId, String userId, int dayNumber,
                                    String placeId, String visitTime) {
        Place raw = placeRepo.findByPlaceId(placeId)
                .orElseThrow(() -> ApiException.badRequest("존재하지 않는 장소입니다."));

        for (int attempt = 0; attempt < MAX_POSITION_RETRY; attempt++) {
            try {
                return txTemplate.execute(s -> {
                    TourPlan plan = findOwnedPlan(planId, userId, "플랜 수정 권한이 없습니다.");
                    if (dayNumber > plan.getTravelDays()) {
                        throw ApiException.badRequest("day_number 가 범위를 벗어났습니다: " + dayNumber);
                    }
                    List<TourPlanItem> dayItems = itemRepo.findByPlanId(planId).stream()
                            .filter(i -> i.getDayNumber() == dayNumber).toList();
                    double pos = dayItems.isEmpty()
                            ? POSITION_SPACING
                            : dayItems.get(dayItems.size() - 1).getPosition() + POSITION_SPACING;

                    TourPlanItem item = itemRepo.saveAndFlush(new TourPlanItem(
                            planId, dayNumber, pos, placeId,
                            raw.getDisplayName(), raw.getAddress(), visitTime));
                    plan.touch();
                    planRepo.flush();
                    return PlanItemResponse.of(item, raw.getRating(), raw.getPhotos());
                });
            } catch (DataIntegrityViolationException e) {
                if (attempt == MAX_POSITION_RETRY - 1) {
                    throw ApiException.badRequest("카드 추가 경합으로 저장에 실패했습니다. 잠시 후 다시 시도해주세요.");
                }
                // 새 트랜잭션 롤백 → 다음 iteration 에서 max position 재조회
            }
        }
        throw ApiException.badRequest("카드 추가 경합으로 저장에 실패했습니다. 잠시 후 다시 시도해주세요.");
    }

    // ──────────────────── 카드 교체 (PUT) ────────────────────

    public PlanItemResponse updateItem(String itemId, String userId, String placeId,
                                       String visitTime, String expectedPlanId) {
        // 장소 조회(Mongo)는 트랜잭션 밖에서 — 네트워크 왕복 동안 RDB 커넥션을 점유하지 않는다(addItem 과 동일).
        Place raw = placeRepo.findByPlaceId(placeId)
                .orElseThrow(() -> ApiException.badRequest("존재하지 않는 장소입니다."));

        return txTemplate.execute(s -> {
            TourPlanItem item = findItemInPlan(itemId, expectedPlanId);
            TourPlan plan = requirePlanOfItem(item.getPlanId());
            if (!plan.getUserId().equals(userId)) {
                throw ApiException.forbidden("카드 수정 권한이 없습니다.");
            }
            item.replace(placeId, raw.getDisplayName(), raw.getAddress(), visitTime);
            plan.touch();
            return PlanItemResponse.of(item, raw.getRating(), raw.getPhotos());
        });
    }

    // ──────────────────── 카드 이동 (position 경합 재시도) ────────────────────

    public void moveItem(String itemId, String userId, int targetDayNumber,
                         String afterItemId, String expectedPlanId) {
        for (int attempt = 0; attempt < MAX_POSITION_RETRY; attempt++) {
            try {
                txTemplate.executeWithoutResult(s -> {
                    TourPlanItem item = findItemInPlan(itemId, expectedPlanId);
                    TourPlan plan = requirePlanOfItem(item.getPlanId());
                    if (!plan.getUserId().equals(userId)) {
                        throw ApiException.forbidden("카드 수정 권한이 없습니다.");
                    }
                    if (targetDayNumber > plan.getTravelDays()) {
                        throw ApiException.badRequest("day_number 가 범위를 벗어났습니다: " + targetDayNumber);
                    }
                    // 자기 자신 뒤로 이동 = 제자리 유지 no-op. dayItems 가 item 을 제외하므로 그대로 두면
                    // computePosition 이 afterItemId 를 못 찾아 오해성 400 을 던진다.
                    if (afterItemId != null && afterItemId.equals(itemId)) {
                        return;
                    }
                    List<TourPlanItem> dayItems = itemRepo.findByPlanId(item.getPlanId()).stream()
                            .filter(i -> i.getDayNumber() == targetDayNumber
                                    && !i.getItemId().equals(item.getItemId()))
                            .toList();
                    double newPos = computePosition(dayItems, afterItemId);

                    item.moveTo(targetDayNumber, newPos);
                    plan.touch();
                    itemRepo.flush();
                });
                return;
            } catch (DataIntegrityViolationException e) {
                if (attempt == MAX_POSITION_RETRY - 1) {
                    throw ApiException.badRequest("카드 이동 경합으로 저장에 실패했습니다. 잠시 후 다시 시도해주세요.");
                }
                // 새 트랜잭션 롤백 → 다음 iteration 에서 day_items 재조회
            }
        }
    }

    // ──────────────────── 카드 삭제 ────────────────────

    @Transactional
    public void removeItem(String itemId, String userId, String expectedPlanId) {
        TourPlanItem item = findItemInPlan(itemId, expectedPlanId);
        TourPlan plan = requirePlanOfItem(item.getPlanId());
        if (!plan.getUserId().equals(userId)) {
            throw ApiException.forbidden("카드 삭제 권한이 없습니다.");
        }
        itemRepo.delete(item);
        plan.touch();
    }

    // ──────────────────── position 계산 / 조회 헬퍼 ────────────────────

    /**
     * day_items(position ASC) 중 after_item_id 다음 자리의 position 계산.
     * 빈 day → SPACING / after=null → first/2 / 마지막 뒤 → last+SPACING / 그 외 → 두 이웃 평균.
     */
    private static double computePosition(List<TourPlanItem> dayItems, String afterItemId) {
        if (dayItems.isEmpty()) {
            return POSITION_SPACING;
        }
        if (afterItemId == null) {
            return dayItems.get(0).getPosition() / 2;
        }
        for (int idx = 0; idx < dayItems.size(); idx++) {
            TourPlanItem it = dayItems.get(idx);
            if (it.getItemId().equals(afterItemId)) {
                if (idx == dayItems.size() - 1) {
                    return it.getPosition() + POSITION_SPACING;
                }
                return (it.getPosition() + dayItems.get(idx + 1).getPosition()) / 2;
            }
        }
        throw ApiException.badRequest("after_item_id 가 해당 day 에 없습니다: " + afterItemId);
    }

    private TourPlan findOwnedPlan(String planId, String userId, String forbiddenMessage) {
        TourPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new TourPlanNotFoundException("존재하지 않는 플랜입니다."));
        if (!plan.getUserId().equals(userId)) {
            throw ApiException.forbidden(forbiddenMessage);
        }
        return plan;
    }

    private TourPlanItem findItemInPlan(String itemId, String expectedPlanId) {
        TourPlanItem item = itemRepo.findById(itemId).orElse(null);
        if (item == null || (expectedPlanId != null && !item.getPlanId().equals(expectedPlanId))) {
            throw new TourPlanItemNotFoundException("존재하지 않는 카드입니다.");
        }
        return item;
    }

    private TourPlan requirePlanOfItem(String planId) {
        return planRepo.findById(planId)
                .orElseThrow(() -> new TourPlanItemNotFoundException("존재하지 않는 카드입니다."));
    }

    /** title 정규화 — 양 끝 공백 제거, 공백만이면 400. */
    private static String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String stripped = title.strip();
        if (stripped.isEmpty()) {
            throw ApiException.badRequest("title 은 공백만으로 구성될 수 없습니다.");
        }
        return stripped;
    }

    private static PlanDetailResponse toDetailResponse(TourPlan plan, List<TourPlanItem> items,
                                                       Map<String, Place> placeMap) {
        List<PlanItemResponse> itemResponses = items.stream().map(i -> {
            Place raw = placeMap.get(i.getPlaceId());
            Double rating = raw != null ? raw.getRating() : null;
            List<String> photos = raw != null ? raw.getPhotos() : List.of();
            return PlanItemResponse.of(i, rating, photos);
        }).toList();

        return new PlanDetailResponse(
                plan.getPlanId(), plan.getUserId(), plan.getTitle(), plan.getTravelDays(),
                plan.getCreatedAt(), plan.getUpdatedAt(), itemResponses);
    }
}
