from fastapi import APIRouter

from app.domain.menu_ai.router import menu_ai_router
from app.domain.translation.router import translation_router
from app.domain.tour.router import tour_router


api_router = APIRouter(prefix="/api")

api_router.include_router(menu_ai_router)
api_router.include_router(translation_router)
api_router.include_router(tour_router)
