# Krip AI Server

핵심 AI 추론 전용 FastAPI 서버. DB·인증·영속화는 Spring(`backend-spring`)이 담당하고,
이 서버는 Spring 이 내부망으로 호출하는 무상태 추론만 수행한다.

## 엔드포인트 (Spring → AI, 서비스 토큰 `ACCESS_TOKEN` 필요)
- `POST /api/translation/detect`, `/api/translation/translate` — Papago
- `POST /api/menu-ai/ocr`, `/api/menu-ai/ocr/batch` — Gemini OCR
- `POST /api/tour/build-plan` — Gemini 일정 생성 (후보/추가 장소는 Spring 이 조회해 전달)
- `GET /health`(liveness), `/ready`(모델 로드 확인) — 인증 면제

## 구조 (backend-fastapi 컨벤션)
```
app/
├── core/ai/{menu_ocr, papago_translator, tour_planner}  # AI 코어
├── core/{llm_manager, logger, instrumentation(ai), metric(ai)}
├── domain/{menu_ai, translation, tour}/{router,service,schema,dto}
├── config/setting.py     # ACCESS_TOKEN / GEMINI / PAPAGO + 로깅만
├── middleware/{auth(BearerToken), tracking}
├── container.py          # DI (menu_ocr / translation / recommend)
└── main.py               # 모델 로드만, DB/Redis/워커 없음
```
