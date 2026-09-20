FROM gradle:9.5.0-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY shared-protocol ./shared-protocol
COPY backend ./backend
RUN gradle --no-daemon :backend:installDist

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 fandroid
WORKDIR /app
COPY --from=build /workspace/backend/build/install/backend/ ./
USER fandroid
EXPOSE 8080
ENTRYPOINT ["/app/bin/backend"]
