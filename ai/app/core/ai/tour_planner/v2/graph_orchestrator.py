from typing import Any, Dict, List, Optional
import math
from functools import lru_cache

from app.core.logger import get_logger
from app.core.ai.tour_planner.v2.data_state import (
    FoodPreference,
    TourBudgetItem,
    TourDayInput,
    TourDayPlan,
    TourMovementHop,
    TourPlaceDetail,
    TourPlanLocation,
    TourPlanResult,
    TourTimelineSlot,
)
from app.core.ai.tour_planner.v2.chain_builder import get_tour_planner_chain_builder
from app.core.ai.tour_planner.v2.category import GROUP_OTHER


logger = get_logger("Tour Planner v2 Orchestrator")


class TourPlannerGraphOrchestrator:
    """Tour Planner v2 오케스트레이터 (AI 서버 — 추론 전용).

    후보 장소/추가 장소는 호출측(Spring)이 DB 에서 조회해 넘긴다. 여기서는 일자별로 LLM 을
    순차 호출(build_day_plan 체인)하고 제약을 적용해 플랜을 만든다. DB 접근/검색 노드는 없다.
    """


    def __init__(self):
        self._chain_manager = get_tour_planner_chain_builder()


    async def initialize(self) -> None:
        self._chain_manager.build_all_chains()


    async def _run_day_plans(
        self,
        *,
        days_input: List[TourDayInput],
        fixed_places: List[Optional[dict]],
        candidate_places: List[List[dict]],
        food_preference: FoodPreference,
    ) -> TourPlanResult:
        """일자별로 LLM을 순차 호출하고 제약을 적용해 전체 플랜을 만든다.

        DB 접근이 없는 순수 추론 단계 — 그래프 노드(_build_day_plan)와 외부 진입점(build_plan)이 공유한다.
        """
        chain = self._chain_manager.get_chain('build_day_plan')

        used_place_ids: List[str] = []
        day_plans: List[TourDayPlan] = []

        for i, day_input in enumerate(days_input):
            day_num = i + 1
            candidates_block = self._format_candidates_block(candidate_places[i])
            additional_block = self._format_additional_block(fixed_places[i])

            day_plan: TourDayPlan = await chain.ainvoke({
                "day": day_num,
                "departure_cluster": day_input.departure_cluster,
                "arrival_cluster": day_input.arrival_cluster,
                "start_time": day_input.start_time,
                "end_time": day_input.end_time,
                "companion": day_input.companion,
                "budget_per_person_krw": day_input.budget_per_person_krw,
                "styles": ", ".join(day_input.styles),
                "schedule_density": day_input.schedule_density,
                "transport": day_input.transport,
                "food_preference": food_preference,
                "additional_place_block": additional_block,
                "used_place_ids": (
                    ", ".join(used_place_ids) if used_place_ids else "(none)"
                ),
                "candidates_block": candidates_block,
            })

            day_plan = self._enforce_constraints(
                day_plan,
                day_input=day_input,
                fixed_place=fixed_places[i],
                candidates=candidate_places[i],
                food_preference=food_preference,
            )

            for p in day_plan.places:
                if p.is_additional:
                    continue
                used_place_ids.append(p.place_id)

            day_plans.append(day_plan)
            logger.info(
                "Day {:d} 플랜 생성 완료: 장소 {:d}, 슬롯 {:d}, 예산 {:d}원",
                day_num, len(day_plan.places), len(day_plan.timeline), day_plan.budget_total_krw,
            )

        return TourPlanResult(tour_plan=day_plans)


    async def build_plan(
        self,
        food_preference: FoodPreference,
        days: List[Dict[str, Any]],
    ) -> TourPlanResult:
        """DB 없는 추론 진입점 — 후보/추가 장소는 호출측(Spring)이 모두 조회해 넘긴다.

        days[i] = {"day_input": TourDayInput, "fixed_place": dict|None, "candidate_places": list[dict]}
        """
        return await self._run_day_plans(
            days_input=[d["day_input"] for d in days],
            fixed_places=[d.get("fixed_place") for d in days],
            candidate_places=[d.get("candidate_places") or [] for d in days],
            food_preference=food_preference,
        )


    # ──────────────────── 후처리 검증 ────────────────────


    @staticmethod
    def _enforce_constraints(
        day_plan: TourDayPlan,
        *,
        day_input: TourDayInput,
        fixed_place: Optional[dict],
        candidates: List[dict],
        food_preference: str,
    ) -> TourDayPlan:
        """LLM 출력에 대한 강제 제약을 적용한다.

        1) 후보 풀 외 place_id 제거 (추가 장소는 예외)
        2) 식당 음식 필터(types 기반) 위반 제거 (추가 장소는 예외)
        3) is_additional 플래그 정합성 강제 (C4)
        4) 추가 장소 누락 시 places + timeline에 강제 삽입
        5) timeline place_id가 places와 일치하도록 정리 + 시각 보간(B7)
        6) movements 정합성 검증 (B8)
        7) budget_total 동기화 (β)
        8) places가 비면 ValueError (B9)
        """
        candidate_index = {p["place_id"]: p for p in candidates}

        allowed_food_types: Optional[set[str]] = None
        if food_preference == "halal":
            allowed_food_types = {"halal_restaurant"}
        elif food_preference == "vegetarian":
            allowed_food_types = {"vegan_restaurant", "vegetarian_restaurant"}

        fixed_pid = fixed_place["place_id"] if fixed_place else None

        # 1~3) 장소 정리 + is_additional 보정 + photos 주입 (LLM은 photos를 모름 → 서버가 DB 원본으로 강제 덮어쓰기)
        kept_places: List[TourPlaceDetail] = []
        for place in day_plan.places:
            if fixed_pid and place.place_id == fixed_pid:
                place = place.model_copy(update={
                    "is_additional": True,
                    "photos": fixed_place.get("photos") or [],
                })
                kept_places.append(place)
                continue

            source = candidate_index.get(place.place_id)
            if source is None:
                logger.warning(
                    "후보 풀 외 장소 제거: {} ({})",
                    place.place_id, place.display_name,
                )
                continue

            if allowed_food_types is not None:
                types = source.get("types") or []
                if "restaurant" in types and not (set(types) & allowed_food_types):
                    logger.warning(
                        "음식 필터({}) 위반 식당 제거: {}",
                        food_preference, place.display_name,
                    )
                    continue

            update: dict = {"photos": source.get("photos") or []}
            if place.is_additional:
                update["is_additional"] = False
            place = place.model_copy(update=update)

            kept_places.append(place)

        # 4) 추가 장소 강제 포함
        if fixed_place and not any(p.place_id == fixed_pid for p in kept_places):
            logger.warning("추가 장소 누락 → 강제 삽입: {}", fixed_pid)
            kept_places.append(_fixed_place_to_detail(fixed_place))

        # 8) 빈 places 거부
        if not kept_places:
            raise ValueError(
                f"Day {day_plan.day}: no valid places remained after enforcement"
            )

        kept_pids = {p.place_id for p in kept_places}

        # 5) timeline 정리 + 추가 장소 누락 시 보강
        # place_id가 없거나(null) 후보 풀에 없는 슬롯은 모두 제거 (transit/anchor 슬롯 차단)
        kept_timeline = [
            slot for slot in day_plan.timeline
            if slot.place_id and slot.place_id in kept_pids
        ]
        if fixed_place and not any(
            slot.place_id == fixed_pid for slot in kept_timeline
        ):
            insert_index = max(1, len(kept_timeline) // 2) if kept_timeline else 0
            prev_time = (
                kept_timeline[insert_index - 1].time
                if 0 < insert_index <= len(kept_timeline) else None
            )
            next_time = (
                kept_timeline[insert_index].time
                if insert_index < len(kept_timeline) else None
            )
            insert_time = TourPlannerGraphOrchestrator._interpolate_time(
                prev_time, next_time, default=day_input.start_time,
            )
            kept_timeline.insert(
                insert_index,
                TourTimelineSlot(
                    time=insert_time,
                    place_id=fixed_pid,
                    title=(
                        f"{fixed_place.get('display_name', 'Additional place')} "
                        f"→ User-designated visit"
                    ),
                ),
            )

        # 6) movements 정합성: from/to가 places.display_name 집합 안에 있어야 함
        place_names = {p.display_name for p in kept_places}
        kept_movements = [
            m for m in day_plan.movements
            if m.from_place in place_names and m.to_place in place_names
        ]
        if len(kept_movements) != len(day_plan.movements):
            logger.warning(
                "movements 정리: {}건 → {}건 (places에 없는 이름 제거)",
                len(day_plan.movements), len(kept_movements),
            )

        # ε. movements가 0건이면 timeline + 좌표 기반으로 fallback 자동 생성
        if not kept_movements and len(kept_places) >= 2:
            kept_movements = TourPlannerGraphOrchestrator._build_fallback_movements(
                kept_timeline, kept_places,
            )
            if kept_movements:
                logger.info(
                    "movements fallback 자동 생성: {}건",
                    len(kept_movements),
                )

        # 7) budget 동기화 — places의 비용과 breakdown/total을 한 방향으로 정합화
        #
        #    원칙: breakdown이 진실. budget_total은 항상 breakdown 합과 일치.
        #    breakdown이 비어있으면 places.estimated_cost_krw 합으로 단일 항목을 합성한다
        #    (장소 카드에는 비용이 표시되는데 합계는 0인 모순을 막기 위함).
        if day_plan.budget_breakdown:
            kept_breakdown = day_plan.budget_breakdown
            budget_total = sum(item.amount_krw for item in kept_breakdown)
            if budget_total != day_plan.budget_total_krw:
                logger.warning(
                    "budget_total 불일치 보정: LLM={} → 합산={}",
                    day_plan.budget_total_krw, budget_total,
                )
        else:
            place_cost_sum = sum(p.estimated_cost_krw for p in kept_places)
            if place_cost_sum > 0:
                kept_breakdown = [
                    TourBudgetItem(
                        label="Estimated daily total",
                        amount_krw=place_cost_sum,
                    )
                ]
                budget_total = place_cost_sum
                logger.warning(
                    "budget_breakdown 비어있음 → places 비용 합({}원)으로 단일 항목 합성",
                    place_cost_sum,
                )
            else:
                kept_breakdown = []
                budget_total = 0
                if day_plan.budget_total_krw > 0:
                    logger.warning(
                        "budget_breakdown·places 비용 모두 비어있음 → budget_total {} 무시하고 0으로 강제",
                        day_plan.budget_total_krw,
                    )

        if budget_total > day_input.budget_per_person_krw:
            logger.warning(
                "예산 초과 감지: 합계 {} > 입력 {} (응답 유지, 경고만)",
                budget_total, day_input.budget_per_person_krw,
            )

        return TourDayPlan(
            day=day_plan.day,
            timeline=kept_timeline,
            places=kept_places,
            movements=kept_movements,
            budget_breakdown=kept_breakdown,
            budget_total_krw=budget_total,
            summary=day_plan.summary,
        )


    # ──────────────────── movements fallback 헬퍼 ────────────────────


    @staticmethod
    def _build_fallback_movements(
        timeline: List[TourTimelineSlot],
        places: List[TourPlaceDetail],
    ) -> List[TourMovementHop]:
        """timeline place_id 순서를 따라 인접 두 장소 사이의 movement를 자동 생성.

        - 같은 place_id 연속 등장(체류 슬롯)은 한 번으로 압축
        - 거리(haversine) 기준으로 method 텍스트를 휴리스틱 결정
        """
        if not timeline or len(places) < 2:
            return []

        place_by_pid = {p.place_id: p for p in places}

        # timeline에서 유효 place_id만 순서대로 추출, 연속 중복 압축
        ordered_pids: List[str] = []
        for slot in timeline:
            pid = slot.place_id
            if pid is None or pid not in place_by_pid:
                continue
            if ordered_pids and ordered_pids[-1] == pid:
                continue
            ordered_pids.append(pid)

        if len(ordered_pids) < 2:
            return []

        movements: List[TourMovementHop] = []
        for prev_pid, next_pid in zip(ordered_pids, ordered_pids[1:]):
            prev_p = place_by_pid[prev_pid]
            next_p = place_by_pid[next_pid]
            distance_m = TourPlannerGraphOrchestrator._haversine_m(
                prev_p.location.lat, prev_p.location.lng,
                next_p.location.lat, next_p.location.lng,
            )
            movements.append(
                TourMovementHop(
                    from_place=prev_p.display_name,
                    to_place=next_p.display_name,
                    method=TourPlannerGraphOrchestrator._describe_movement(distance_m),
                )
            )
        return movements


    @staticmethod
    def _describe_movement(distance_m: float) -> str:
        """거리(미터)를 사용자에게 보여줄 영문 method 텍스트로 변환.

        - < 800m  : 도보 (분당 80m 기준)
        - 800m~3km: 도보 또는 지하철 1~2 정거장
        - >= 3km  : 지하철/버스
        """
        if distance_m < 800:
            mins = max(1, round(distance_m / 80))
            return f"{mins} min walk"
        if distance_m < 3000:
            mins = max(1, round(distance_m / 80))
            return f"{mins} min walk or subway 1-2 stops"
        km = max(1, round(distance_m / 1000))
        return f"Subway / bus (~{km} km)"


    @staticmethod
    def _haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
        """두 좌표 간 구면 거리 (m)."""
        R = 6_371_000.0
        p1, p2 = math.radians(lat1), math.radians(lat2)
        dp = math.radians(lat2 - lat1)
        dl = math.radians(lng2 - lng1)
        a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
        return 2 * R * math.asin(math.sqrt(a))


    # ──────────────────── 시각 보간 헬퍼 ────────────────────


    @staticmethod
    def _interpolate_time(
        prev: Optional[str], next_: Optional[str], default: str,
    ) -> str:
        """두 HH:MM 시각의 중간값을 산출. 한쪽만 있으면 ±60분, 둘 다 없으면 default."""
        def parse(t: Optional[str]) -> Optional[int]:
            if not t:
                return None
            try:
                h, m = t.split(":")
                return int(h) * 60 + int(m)
            except (ValueError, AttributeError):
                return None

        def fmt(mins: int) -> str:
            mins = max(0, min(23 * 60 + 59, mins))
            return f"{mins // 60:02d}:{mins % 60:02d}"

        p, n = parse(prev), parse(next_)
        if p is not None and n is not None and n > p:
            return fmt((p + n) // 2)
        if p is not None:
            return fmt(p + 60)
        if n is not None:
            return fmt(n - 60)
        return default


    # ──────────────────── 포맷팅 유틸 ────────────────────


    @staticmethod
    def _format_additional_block(fixed_place: Optional[dict]) -> str:
        if fixed_place is None:
            return "(none)"
        coords = fixed_place.get("location", {}).get("coordinates", [0, 0])
        lng, lat = coords[0], coords[1]
        summary = (
            fixed_place.get("editorial_summary")
            or fixed_place.get("generative_summary")
            or fixed_place.get("review_summary")
            or ""
        )
        lines = [
            f"- place_id: {fixed_place['place_id']}",
            f"- name: {fixed_place['display_name']}",
            f"- category: {fixed_place.get('category', '')}",
            f"- address: {fixed_place.get('short_address') or fixed_place.get('address', '')}",
            f"- coords: ({lat}, {lng})",
        ]
        if summary:
            lines.append(f"- summary: {summary}")
        return "\n".join(lines)


    @staticmethod
    def _format_candidates_block(places: List[dict]) -> str:
        if not places:
            return "(no candidates)"
        lines: List[str] = []
        for i, place in enumerate(places, 1):
            coords = place.get("location", {}).get("coordinates", [0, 0])
            lng, lat = coords[0], coords[1]
            summary = (
                place.get("editorial_summary")
                or place.get("generative_summary")
                or place.get("review_summary")
                or ""
            )
            group = place.get("_group", GROUP_OTHER)
            lines.append(
                f"[{i}] {place['display_name']} ({place.get('category', '')}) "
                f"[GROUP: {group}]"
            )
            lines.append(f"  - place_id: {place['place_id']}")
            types = place.get("types") or []
            if types:
                lines.append(f"  - types: {', '.join(types[:8])}")
            lines.append(
                f"  - address: {place.get('short_address') or place.get('address', '')}"
            )
            lines.append(f"  - coords: ({lat}, {lng})")
            if place.get("rating"):
                rating_str = f"  - rating: {place['rating']}"
                if place.get("rating_count"):
                    rating_str += f" ({place['rating_count']:,} reviews)"
                lines.append(rating_str)
            if place.get("price_level"):
                lines.append(f"  - price level: {place['price_level']}")
            if summary:
                lines.append(f"  - summary: {summary}")
            if place.get("opening_hours"):
                lines.append(f"  - hours: {' / '.join(place['opening_hours'][:3])}")
            lines.append("")
        return "\n".join(lines)


def _fixed_place_to_detail(fixed_place: dict) -> TourPlaceDetail:
    """추가 장소 dict를 TourPlaceDetail로 변환 (강제 삽입 시 사용).

    LLM이 응답에서 누락해 강제 삽입되는 fallback 경로에서만 호출된다.
    추가 장소는 대개 무료 관광지(공원·landmark·거리)라 estimated_cost_krw=0으로
    채워 클라이언트가 "Free"로 일관되게 표시할 수 있도록 한다.
    """
    coords = fixed_place.get("location", {}).get("coordinates", [0, 0])
    lng, lat = coords[0], coords[1]
    summary = (
        fixed_place.get("editorial_summary")
        or fixed_place.get("generative_summary")
        or fixed_place.get("review_summary")
        or "User-designated must-visit place."
    )
    return TourPlaceDetail(
        place_id=fixed_place["place_id"],
        display_name=fixed_place["display_name"],
        category=fixed_place.get("category", ""),
        address=fixed_place.get("short_address") or fixed_place.get("address", ""),
        location=TourPlanLocation(lat=lat, lng=lng),
        rating=fixed_place.get("rating"),
        reason=summary,
        estimated_cost_krw=0,
        stay_minutes=60,
        is_additional=True,
        photos=fixed_place.get("photos") or [],
    )


@lru_cache(maxsize=1)
def get_tour_planner_graph() -> TourPlannerGraphOrchestrator:
    """싱글톤 인스턴스 반환."""
    return TourPlannerGraphOrchestrator()
