from fastapi import APIRouter

from app.domain.tour.router import build_plan


tour_router = APIRouter(prefix="/tour")
tour_router.include_router(build_plan.router)
