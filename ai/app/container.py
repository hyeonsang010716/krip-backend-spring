from dependency_injector import containers, providers

from app.domain.menu_ai.service.menu_ocr import MenuOcrService
from app.domain.translation.service.translation import TranslationService
from app.domain.tour.service.recommend import RecommendService


class Container(containers.DeclarativeContainer):
    """DI Container — AI 서비스만 선언. 모두 무상태라 DB/UoW 의존이 없다."""

    # 메뉴 OCR (Gemini)
    menu_ocr_service = providers.Factory(MenuOcrService)

    # 번역/언어감지 (Papago)
    translation_service = providers.Factory(TranslationService)

    # 여행 추천 — build-plan(LLM 일정 생성)만. 후보/추가 장소는 호출측(Spring)이 주입.
    recommend_service = providers.Factory(RecommendService)
