from google.api_core.exceptions import (
    GoogleAPICallError,
    PermissionDenied,
    ResourceExhausted,
    Unauthenticated,
)

from app.domain.tour.service.exception import (
    TourRecommendCredentialExpiredError,
    TourRecommendQuotaExceededError,
    TourRecommendVendorError,
)
from app.domain.tour.schema.recommend import (
    TourBudgetItemResponse,
    TourDayResponse,
    TourMovementHopResponse,
    TourPlaceDetailResponse,
    TourPlaceLocationResponse,
    TourRecommendResponse,
    TourTimelineSlotResponse,
)
from app.domain.tour.schema.build_plan import TourBuildPlanRequest
from app.core.ai.tour_planner.v2.data_state import TourPlanResult
from app.core.ai.tour_planner.load import TourPlanner


class RecommendService:
    """여행 추천 서비스 (AI 서버).

    DB 조회(추가 장소·후보 풀)는 호출측(Spring)이 끝내고 build-plan 으로 넘긴다.
    여기서는 LLM 일정 생성(TourPlanner.build_plan) + 결과를 응답 스키마로 변환만 한다.
    Tour Planner 는 무상태 싱글톤이라 UoW 를 받지 않는다.
    """

    def __init__(self) -> None:
        self._planner = TourPlanner()

    async def build_plan(self, body: TourBuildPlanRequest) -> TourRecommendResponse:
        """후보/추가 장소가 이미 채워진 입력으로 LLM 플랜만 생성한다."""
        days = [
            {
                "day_input": d.day_input,
                "fixed_place": d.fixed_place,
                "candidate_places": d.candidate_places,
            }
            for d in body.days
        ]

        try:
            result = await self._planner.build_plan(
                food_preference=body.food_preference,
                days=days,
            )
        except (Unauthenticated, PermissionDenied) as e:
            raise TourRecommendCredentialExpiredError(str(e)) from e
        except ResourceExhausted as e:
            raise TourRecommendQuotaExceededError(str(e)) from e
        except GoogleAPICallError as e:
            raise TourRecommendVendorError(str(e)) from e

        return self._to_response(result)

    # ──────────────────── Planner 결과 → Response ────────────────────

    @staticmethod
    def _to_response(result: TourPlanResult) -> TourRecommendResponse:
        """TourPlanResult → TourRecommendResponse 매핑."""
        return TourRecommendResponse(
            tour_plan=[
                TourDayResponse(
                    day=day.day,
                    timeline=[
                        TourTimelineSlotResponse(
                            time=slot.time,
                            place_id=slot.place_id,
                            title=slot.title,
                        )
                        for slot in day.timeline
                    ],
                    places=[
                        TourPlaceDetailResponse(
                            place_id=p.place_id,
                            display_name=p.display_name,
                            category=p.category,
                            address=p.address,
                            location=TourPlaceLocationResponse(
                                lat=p.location.lat, lng=p.location.lng
                            ),
                            rating=p.rating,
                            reason=p.reason,
                            estimated_cost_krw=p.estimated_cost_krw,
                            stay_minutes=p.stay_minutes,
                            photos=p.photos,
                        )
                        for p in day.places
                    ],
                    movements=[
                        TourMovementHopResponse(
                            from_place=m.from_place,
                            to_place=m.to_place,
                            method=m.method,
                        )
                        for m in day.movements
                    ],
                    budget_breakdown=[
                        TourBudgetItemResponse(label=b.label, amount_krw=b.amount_krw)
                        for b in day.budget_breakdown
                    ],
                    budget_total_krw=day.budget_total_krw,
                    summary=day.summary,
                )
                for day in result.tour_plan
            ],
        )
