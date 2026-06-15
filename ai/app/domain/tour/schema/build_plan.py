"""Tour build-plan(내부) 스키마.

Spring이 DB 조회(추가 장소 + 후보 풀)를 모두 끝낸 뒤 호출하는 무상태 추론 진입점의 입출력.
응답은 추천 응답 스키마 TourRecommendResponse 를 재사용한다.
"""
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field

from app.core.ai.tour_planner.v2.data_state import FoodPreference, TourDayInput


class TourBuildPlanDay(BaseModel):
    """일자별 사전 조회 데이터."""

    day_input: TourDayInput = Field(..., description="일자별 사용자 입력")
    fixed_place: Optional[Dict[str, Any]] = Field(None, description="추가(필수 포함) 장소 원본 dict. 없으면 null")
    candidate_places: List[Dict[str, Any]] = Field(
        default_factory=list, description="그룹 균형 분배까지 끝난 후보 장소 dict 목록(각 항목에 _group 포함)"
    )


class TourBuildPlanRequest(BaseModel):
    """전체 build-plan 요청."""

    food_preference: FoodPreference = Field(..., description="음식 옵션 (halal / vegetarian / any)")
    days: List[TourBuildPlanDay] = Field(..., description="일자별 입력 + 사전 조회 데이터")
