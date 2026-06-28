# ──────────────────── build stage ────────────────────
# 개발은 맥북(M4, arm64) Docker, 배포는 Ubuntu(Intel, amd64). 멀티아키 호환 base 사용.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /build

# 의존성·Gradle 배포 레이어 캐시 — 빌드 스크립트만 먼저 복사해 resolve.
COPY gradlew settings.gradle build.gradle lombok.config ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies --configuration compileClasspath --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ──────────────────── runtime stage ────────────────────
FROM eclipse-temurin:17-jre-jammy
WORKDIR /backend

# compose/k8s 헬스체크가 actuator 프로브를 찌를 HTTP 클라이언트 + 비특권 유저.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --user-group --no-create-home krip

COPY --from=build /build/build/libs/backend-0.3.0.jar app.jar

# 비특권 유저로 구동 — RCE/컨테이너 탈출 시 권한 축소. (마운트된 secrets 가 이 유저에게 읽혀야 함)
USER krip

# 힙은 컨테이너 메모리 한도(compose mem_limit)의 70%, OOM 시 즉시 종료해 restart 로 자가복구.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8000
# sh -c 로 JAVA_OPTS 를 펼치되 exec 로 java 를 PID 1 화 — SIGTERM→graceful shutdown 을 보존.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
