"""Prometheus 메트릭 정의 — AI 전용 서버라 AI 메트릭만 둔다.

원본(backend-fastapi)의 chat/db/mongo/redis/fcm/worker 메트릭은 이 서버에 해당 기능이 없어 제외한다.
외부에선 `from app.core.metric import X`.
"""
from app.core.metric.ai import (
    AI_EXTERNAL_CALL_DURATION,
    AI_EXTERNAL_CALL_TOTAL,
    AI_INFERENCE_DURATION,
    AI_INFERENCE_TOTAL,
    AI_MODEL_LOAD_DURATION,
    AI_TOKEN_USAGE_TOTAL,
)


__all__ = (
    "AI_EXTERNAL_CALL_DURATION",
    "AI_EXTERNAL_CALL_TOTAL",
    "AI_INFERENCE_DURATION",
    "AI_INFERENCE_TOTAL",
    "AI_MODEL_LOAD_DURATION",
    "AI_TOKEN_USAGE_TOTAL",
)
