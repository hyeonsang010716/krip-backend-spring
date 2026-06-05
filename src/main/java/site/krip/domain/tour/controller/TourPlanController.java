package site.krip.domain.tour.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.tour.dto.request.AddItemRequest;
import site.krip.domain.tour.dto.request.CreatePlanRequest;
import site.krip.domain.tour.dto.request.MoveItemRequest;
import site.krip.domain.tour.dto.request.UpdateItemRequest;
import site.krip.domain.tour.dto.request.UpdatePlanRequest;
import site.krip.domain.tour.dto.response.PlanDetailResponse;
import site.krip.domain.tour.dto.response.PlanItemResponse;
import site.krip.domain.tour.dto.response.PlanListResponse;
import site.krip.domain.tour.dto.response.PlanSummaryResponse;
import site.krip.domain.tour.dto.response.ShareTokenResponse;
import site.krip.domain.tour.service.TourPlanService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

/**
 * 여행 플랜 CRUD + 카드 편집. 경로: {@code /api/tour/plans}.
 */
@RestController
@RequestMapping("/api/tour/plans")
public class TourPlanController {

    private final TourPlanService tourPlanService;

    public TourPlanController(TourPlanService tourPlanService) {
        this.tourPlanService = tourPlanService;
    }

    // ──────────────────── 플랜 CRUD ────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanDetailResponse createPlan(@CurrentUserId String userId,
                                         @Valid @RequestBody CreatePlanRequest body) {
        return tourPlanService.createPlan(userId, body);
    }

    @GetMapping
    public PlanListResponse getPlans(@CurrentUserId String userId) {
        return tourPlanService.getPlans(userId);
    }

    @GetMapping("/{plan_id}")
    public PlanDetailResponse getPlan(@CurrentUserId String userId, @PathVariable("plan_id") String planId) {
        return tourPlanService.getPlan(planId, userId);
    }

    @PatchMapping("/{plan_id}")
    public PlanSummaryResponse updatePlan(@CurrentUserId String userId, @PathVariable("plan_id") String planId,
                                          @Valid @RequestBody UpdatePlanRequest body) {
        return tourPlanService.updatePlanTitle(planId, userId, body.title());
    }

    @PostMapping("/{plan_id}/share")
    @ResponseStatus(HttpStatus.CREATED)
    public ShareTokenResponse sharePlan(@CurrentUserId String userId, @PathVariable("plan_id") String planId) {
        return tourPlanService.generateShareToken(planId, userId);
    }

    @PostMapping("/{plan_id}/days")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanSummaryResponse addDay(@CurrentUserId String userId, @PathVariable("plan_id") String planId) {
        return tourPlanService.addDay(planId, userId);
    }

    @DeleteMapping("/{plan_id}/days/{day_number}")
    public MessageResponse removeDay(@CurrentUserId String userId, @PathVariable("plan_id") String planId,
                                     @PathVariable("day_number") int dayNumber) {
        tourPlanService.removeDay(planId, userId, dayNumber);
        return new MessageResponse("일차가 삭제되었습니다.");
    }

    @DeleteMapping("/{plan_id}")
    public MessageResponse deletePlan(@CurrentUserId String userId, @PathVariable("plan_id") String planId) {
        tourPlanService.deletePlan(planId, userId);
        return new MessageResponse("플랜이 삭제되었습니다.");
    }

    // ──────────────────── 카드 편집 ────────────────────

    @PostMapping("/{plan_id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanItemResponse addItem(@CurrentUserId String userId, @PathVariable("plan_id") String planId,
                                    @Valid @RequestBody AddItemRequest body) {
        return tourPlanService.addItem(planId, userId, body.dayNumber(), body.placeId(), body.visitTime());
    }

    @PutMapping("/{plan_id}/items/{item_id}")
    public PlanItemResponse updateItem(@CurrentUserId String userId, @PathVariable("plan_id") String planId,
                                       @PathVariable("item_id") String itemId, @Valid @RequestBody UpdateItemRequest body) {
        return tourPlanService.updateItem(itemId, userId, body.placeId(), body.visitTime(), planId);
    }

    @PatchMapping("/{plan_id}/items/{item_id}/move")
    public MessageResponse moveItem(@CurrentUserId String userId, @PathVariable("plan_id") String planId,
                                    @PathVariable("item_id") String itemId, @Valid @RequestBody MoveItemRequest body) {
        tourPlanService.moveItem(itemId, userId, body.targetDayNumber(), body.afterItemId(), planId);
        return new MessageResponse("카드가 이동되었습니다.");
    }

    @DeleteMapping("/{plan_id}/items/{item_id}")
    public MessageResponse removeItem(@CurrentUserId String userId, @PathVariable("plan_id") String planId,
                                      @PathVariable("item_id") String itemId) {
        tourPlanService.removeItem(itemId, userId, planId);
        return new MessageResponse("카드가 삭제되었습니다.");
    }
}
