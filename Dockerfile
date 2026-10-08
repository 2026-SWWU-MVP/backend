# 빌드: 테스트는 CI에서 먼저 돌리므로 여기서는 jar만 만든다
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar

# 실행
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app \
    && mkdir -p /app/storage && chown app:app /app/storage
COPY --from=build /workspace/build/libs/*.jar app.jar
USER app

# 업로드 파일(기출, 시험범위 자료, 로고) 저장 경로. 배포할 때 볼륨으로 연결해야 재시작해도 남는다
ENV STORAGE_ROOT=/app/storage
VOLUME /app/storage

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
