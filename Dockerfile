# 멀티스테이지 — 빌드는 JDK 이미지에서, 실행은 JRE 이미지에서. 호스트에 JDK 없이 Docker 만으로 실행 가능.
# 본 앱과 Mock 공급사 서버는 같은 빌드 스테이지에서 각자의 bootJar 로 만들어져 별도 타깃 이미지가 된다.

# ── 빌드 스테이지 ─────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 빌드 스크립트를 소스보다 먼저 복사 — 소스만 바뀌면 의존성 해석 레이어는 캐시 재사용
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY mock-supplier/build.gradle.kts ./mock-supplier/
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet > /dev/null || true

COPY src ./src
COPY mock-supplier/src ./mock-supplier/src
# 테스트는 CI 와 로컬 gradlew 가 담당한다 — 이미지 빌드는 실행물 생성만
RUN ./gradlew --no-daemon :bootJar :mock-supplier:bootJar -x test

# ── 본 앱 ────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS app
WORKDIR /app
# 헬스체크용 curl
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# ── Mock 공급사 서버 ─────────────────────────────────────────
FROM eclipse-temurin:21-jre AS mock
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/mock-supplier/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--server.port=9090"]
