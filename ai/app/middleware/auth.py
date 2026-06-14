import hmac
from typing import Callable, Sequence
from starlette.types import ASGIApp
from starlette.middleware.base import BaseHTTPMiddleware
from fastapi import Request, Response
from fastapi.responses import JSONResponse

from app.core.logger import get_logger
from app.config.setting import settings


class BearerTokenMiddleware(BaseHTTPMiddleware):
    """서비스 간 정적 액세스 토큰 검증.

    Spring(게이트웨이)이 `Authorization: Bearer <ACCESS_TOKEN>` 으로 호출한다.
    AI 서버는 내부 전용이라 유저 로그인(JWT) 검증은 두지 않고 게이트웨이 토큰만 본다.
    """

    EXCLUDE_PATHS: Sequence[str] = ("/health", "/ready", "/docs", "/redoc", "/openapi.json")

    def __init__(self, app: ASGIApp) -> None:
        super().__init__(app)
        self.logger = get_logger("middleware.auth")

    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        if request.url.path in self.EXCLUDE_PATHS:
            return await call_next(request)

        authorization = request.headers.get("Authorization")
        if not authorization:
            return JSONResponse(status_code=401, content={"detail": "Authorization 헤더가 필요합니다"})

        parts = authorization.split(" ", 1)
        if len(parts) != 2 or parts[0].lower() != "bearer":
            return JSONResponse(status_code=401, content={"detail": "Bearer 토큰 형식이 올바르지 않습니다"})

        # 타이밍 공격 방지 상수시간 비교.
        if not hmac.compare_digest(parts[1], settings.ACCESS_TOKEN):
            self.logger.warning("유효하지 않은 서비스 토큰 (path={})", request.url.path)
            return JSONResponse(status_code=401, content={"detail": "유효하지 않은 토큰입니다"})

        return await call_next(request)
