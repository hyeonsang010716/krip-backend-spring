"""헬스체크 — /health(liveness), /ready(readiness).

  - /health : 프로세스 기동 여부만. AI 모델 로드 전에도 200.
  - /ready  : AI 모델 3종(Menu OCR, Papago, Tour Planner) 초기화 완료 시 200, 아니면 503.

라우팅: main.py 가 app.include_router(health_router) 로 `/api` prefix 를 우회해 등록한다.
"""
from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.core.ai.tour_planner.load import TourPlanner
from app.core.ai.papago_translator.load import PapagoTranslator
from app.core.ai.menu_ocr.load import MenuOcr


router = APIRouter(tags=["health"])


@router.get("/health")
async def liveness() -> JSONResponse:
    return JSONResponse(status_code=200, content={"status": "ok"})


@router.get("/ready")
async def readiness() -> JSONResponse:
    models = {
        "menu_ocr": getattr(MenuOcr(), "_initialized", False),
        "papago": getattr(PapagoTranslator(), "_initialized", False),
        "tour_planner": getattr(TourPlanner(), "_initialized", False),
    }
    ok = all(models.values())
    return JSONResponse(status_code=200 if ok else 503, content={"ready": ok, "models": models})
