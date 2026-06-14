from fastapi import APIRouter, Depends, HTTPException
from dependency_injector.wiring import Provide, inject

from app.domain.tour.service.recommend import RecommendService
from app.domain.tour.service.exception import (
    TourRecommendCredentialExpiredError,
    TourRecommendQuotaExceededError,
    TourRecommendVendorError,
)
from app.domain.tour.schema.build_plan import TourBuildPlanRequest
from app.domain.tour.schema.recommend import TourRecommendResponse
from app.core.logger import get_logger
from app.container import Container


router = APIRouter(prefix="/build-plan", tags=["여행 추천(내부)"])
logger = get_logger("tour.build_plan")


@router.post("", status_code=200)
@inject
async def build_plan(
    body: TourBuildPlanRequest,
    recommend_service: RecommendService = Depends(Provide[Container.recommend_service]),
) -> TourRecommendResponse:
    """무상태 추론 진입점 — DB 조회(추가 장소·후보 풀)는 호출측(Spring)이 끝내고 넘긴다.

    LLM 일자별 플랜 생성 + 제약 검증만 수행한다.
    """
    try:
        return await recommend_service.build_plan(body)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except TourRecommendCredentialExpiredError as e:
        logger.critical("Gemini 인증 만료 / 권한 거부: {}", e)
        raise HTTPException(status_code=503, detail="여행 추천 서비스가 일시 중단되었습니다.")
    except TourRecommendQuotaExceededError as e:
        logger.warning("Gemini 쿼터 소진: {}", e)
        raise HTTPException(status_code=429, detail="요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해주세요.")
    except TourRecommendVendorError as e:
        logger.error("Gemini 벤더 오류: {}", e)
        raise HTTPException(status_code=502, detail="여행 추천에 실패했습니다.")
