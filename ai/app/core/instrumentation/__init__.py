"""메트릭 instrumentation — AI 전용. 데코레이터/컨텍스트 매니저로 도메인 코드에 한 줄 부착.

메트릭 정의는 `app.core.metric`. 외부에선 `from app.core.instrumentation import X`.
AI-only 서버라 원본의 worker/chat/fcm/db/redis/mongo instrumentation 은 제외한다.
"""
from app.core.instrumentation.ai import (
    AI_MODEL_NAMES,
    AI_PROVIDERS,
    AI_RESULTS,
    GeminiInstrumentationHandler,
    ai_external_call,
    ai_inference,
    ai_model_load_duration_set,
    ai_token_usage_inc,
)


__all__ = (
    "AI_MODEL_NAMES",
    "AI_PROVIDERS",
    "AI_RESULTS",
    "GeminiInstrumentationHandler",
    "ai_external_call",
    "ai_inference",
    "ai_model_load_duration_set",
    "ai_token_usage_inc",
)
