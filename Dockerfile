FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src

RUN --mount=type=cache,id=morning-pilates-gradle,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew installDist --no-daemon

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

COPY --from=build --chown=10001:10001 /workspace/build/install/morning-pilates-bot/ ./

USER 10001:10001
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["/app/bin/morning-pilates-bot"]
