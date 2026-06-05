# ──────────────────── build stage ────────────────────
# 개발은 맥북(M4, arm64) Docker, 배포는 Ubuntu(Intel, amd64). 멀티아키 호환 base 사용.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 의존성 레이어 캐시 — pom 만 먼저 복사해 오프라인 resolve.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ──────────────────── runtime stage ────────────────────
FROM eclipse-temurin:17-jre-jammy
WORKDIR /backend

COPY --from=build /build/target/backend-0.3.0.jar app.jar

EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar"]
