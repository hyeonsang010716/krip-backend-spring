import uvicorn
from contextlib import asynccontextmanager
from fastapi import FastAPI

from app.middleware.tracking import RequestIDMiddleware, SecurityHeadersMiddleware
from app.middleware.auth import BearerTokenMiddleware
from app.core.logger import setup_logging, get_logger
from app.core.ai.tour_planner.load import TourPlanner
from app.core.ai.papago_translator.load import PapagoTranslator
from app.core.ai.menu_ocr.load import MenuOcr
from app.container import Container
from app.config.setting import settings
from app.api.v1.router import api_router
from app.api.v1.health import router as health_router


logger = get_logger("main")


def create_app() -> FastAPI:
    """AI 서버 애플리케이션 팩토리 — DB/Redis/워커 없이 AI 모델만 로드한다."""

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        setup_logging()
        # AI 모델 로드 — 외부 네트워크 없이 클라이언트/체인만 구성한다(추론은 요청 시).
        MenuOcr().load()
        PapagoTranslator().load()
        await TourPlanner().load()
        logger.info("AI server started in {} mode", settings.ENVIRONMENT)
        yield
        await PapagoTranslator().close()
        logger.info("AI server shut down")

    container = Container()
    container.wire(modules=[
        "app.domain.menu_ai.router.menu_ocr",
        "app.domain.translation.router.translation",
        "app.domain.tour.router.build_plan",
    ])

    app = FastAPI(
        title="Krip AI",
        description="핵심 AI 추론 전용 서버 (OCR / 번역 / 여행 일정 생성). DB·인증·영속화는 Spring 이 담당.",
        version="0.1.0",
        docs_url=None if settings.is_production else "/docs",
        redoc_url=None if settings.is_production else "/redoc",
        openapi_url=None if settings.is_production else "/openapi.json",
        lifespan=lifespan,
    )

    # 미들웨어 (등록 역순 실행 → RequestID 가 가장 먼저). 내부 서비스라 CORS 는 두지 않는다.
    app.add_middleware(SecurityHeadersMiddleware)
    app.add_middleware(BearerTokenMiddleware)
    app.add_middleware(RequestIDMiddleware)

    app.include_router(api_router)
    app.include_router(health_router)

    # Swagger Authorize 버튼(DEV 한정) — BearerTokenMiddleware 는 Starlette 미들웨어라 OpenAPI 에
    # 노출되지 않으므로 securityScheme 만 얹어 토큰 입력 칸을 띄운다. 실제 검증은 미들웨어가 수행.
    if not settings.is_production:
        _default_openapi = app.openapi

        def _openapi_with_bearer():
            schema = _default_openapi()
            schema.setdefault("components", {}).setdefault("securitySchemes", {})["BearerAuth"] = {
                "type": "http", "scheme": "bearer",
                "description": "settings.ACCESS_TOKEN 값을 입력. DEV 환경에서만 노출.",
            }
            schema["security"] = [{"BearerAuth": []}]
            return schema

        app.openapi = _openapi_with_bearer

    app.container = container
    return app


app = create_app()


if __name__ == "__main__":
    uvicorn.run(app, host=settings.HOST, port=settings.PORT, log_config=None)
