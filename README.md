# Krip Backend Spring Version

기존 FastAPI 백엔드 Java 17 / Spring Boot 3.5 로 리팩토링한 모듈.

## 실행

```bash
cp .env.example .env
docker compose up --build
```

- API: http://localhost:8000
- Swagger UI: http://localhost:8000/docs

로컬 단독 실행:

```bash
./gradlew bootRun
```