import uuid
from typing import Callable
from starlette.types import ASGIApp
from starlette.middleware.base import BaseHTTPMiddleware
from fastapi import Request, Response

from app.core.logger import get_logger


class RequestIDMiddleware(BaseHTTPMiddleware):
    """요청마다 request_id 를 발급해 request.state 와 응답 헤더(X-Request-ID)에 싣는다.

    클라이언트(Spring)가 X-Request-ID 를 보내면 그대로 전파해 분산 추적을 잇는다.
    """

    def __init__(self, app: ASGIApp) -> None:
        super().__init__(app)
        self.logger = get_logger("middleware.request")

    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        request_id = request.headers.get("X-Request-ID") or uuid.uuid4().hex
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id
        return response


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """기본 보안 응답 헤더 부착. 내부 서비스라 최소한만 둔다."""

    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("X-Frame-Options", "DENY")
        return response
