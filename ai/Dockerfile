# 개발은 맥북(M4, arm64), 배포는 Ubuntu(Intel, amd64) — python:slim 은 멀티아키 제공.
FROM python:3.11.11-slim

WORKDIR /backend

# 빌드 의존성(일부 휠 미제공 패키지 대비) + 헬스체크용 curl.
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl gcc \
    && rm -rf /var/lib/apt/lists/*

# uv 설치
RUN curl -LsSf https://astral.sh/uv/install.sh | sh
ENV PATH="/root/.local/bin:${PATH}"
ENV PYTHONPATH="/backend"

# 의존성 레이어 캐시 — pyproject 만 먼저 복사해 resolve/install.
COPY pyproject.toml ./
RUN uv sync --no-dev

# 애플리케이션 코드
COPY app ./app

EXPOSE 8000

# Spring 내부망에서만 호출되는 AI 추론 서버.
CMD ["uv", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
