from typing import Optional
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field


class Settings(BaseSettings):
    """AI 서버 설정 — 핵심 AI 추론만 담당하므로 DB/Redis/Storage 설정은 두지 않는다."""

    # 서버
    HOST: str = Field("0.0.0.0", description="서버 호스트")
    PORT: int = Field(8000, description="서버 포트")
    ENVIRONMENT: str = Field("DEV", description="환경 (PROD/DEV)")

    # 로깅
    LOG_LEVEL: str = Field("INFO", description="로그 레벨")
    LOG_FORMAT: str = Field("console", description="로그 포맷 (json/console)")
    LOG_FILE_PATH: Optional[str] = Field("/backend/logs/app.log", description="로그 파일 경로 (빈 값이면 콘솔만)")
    LOG_ROTATION: str = Field("100 MB", description="로그 로테이션")
    LOG_RETENTION: str = Field("30 days", description="로그 보관 기준")
    LOG_COMPRESSION: str = Field("gz", description="롤테이션 파일 압축")

    # 인증 — Spring(게이트웨이)과 공유하는 서비스 토큰. 미설정 시 부팅 실패.
    ACCESS_TOKEN: str = Field(..., description="서비스 간 API 접근 토큰")

    # LLM (Gemini)
    GOOGLE_GEMINI_API_KEY: str = Field(..., description="구글 제미나이 API 키")

    # Papago (Naver Developers — 번역/언어 감지)
    PAPAGO_CLIENT_ID: str = Field(..., description="Papago Client ID")
    PAPAGO_CLIENT_SECRET: str = Field(..., description="Papago Client Secret")

    @property
    def is_production(self) -> bool:
        return self.ENVIRONMENT == "PROD"

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore",  # 미선언 변수 허용 (공유 .env 등)
    )


settings = Settings()
