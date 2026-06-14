from typing import Any, Dict, List
import time

from app.core.instrumentation import ai_inference, ai_model_load_duration_set
from app.core.ai.tour_planner.v2.graph_orchestrator import (
    TourPlannerGraphOrchestrator,
    get_tour_planner_graph,
)
from app.core.ai.tour_planner.v2.data_state import (
    FoodPreference,
    TourPlanResult,
)


class TourPlanner:
    """Tour Planner — 후보/추가 장소를 받아 LLM 일정만 생성한다(무상태 싱글톤).

    DB 조회는 호출측(Spring)이 담당하므로 이 서버에는 장소 검색/그래프 노드가 없다.
    """

    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance


    async def load(self) -> None:
        """서버 시작 시 한 번 호출 — LLM 체인을 구성한다(외부 네트워크 없음)."""
        if self._initialized:
            return
        started = time.perf_counter()
        self._orchestrator: TourPlannerGraphOrchestrator = get_tour_planner_graph()
        await self._orchestrator.initialize()
        ai_model_load_duration_set("tour_planner", time.perf_counter() - started)
        self._initialized = True


    async def build_plan(
        self,
        food_preference: FoodPreference,
        days: List[Dict[str, Any]],
    ) -> TourPlanResult:
        """DB 없는 추론 진입점.

        days[i] = {"day_input": TourDayInput, "fixed_place": dict|None, "candidate_places": list[dict]}
        """
        if not self._initialized:
            raise RuntimeError("모델이 로드되지 않았습니다.")

        async with ai_inference("tour_planner"):
            return await self._orchestrator.build_plan(
                food_preference=food_preference,
                days=days,
            )
