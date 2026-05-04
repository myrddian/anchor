# --- Build stage --------------------------------------------------------
# Multi-stage so the final image carries only the JRE + the bootJar, not the
# full JDK + Gradle daemon + ~500MB of cached dependencies.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Copy gradle wrapper + settings first so dep resolution caches independently
# of source changes — most rebuilds skip re-downloading the world.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY anchor-protocol/build.gradle.kts anchor-protocol/
COPY anchor-server/build.gradle.kts anchor-server/
COPY anchor-client/build.gradle.kts anchor-client/
COPY anchor-shell/build.gradle.kts anchor-shell/
RUN chmod +x gradlew && ./gradlew --no-daemon :anchor-server:dependencies > /dev/null 2>&1 || true

# Now the actual sources. Excludes test source via the .dockerignore.
COPY anchor-protocol/src anchor-protocol/src
COPY anchor-server/src anchor-server/src

RUN ./gradlew --no-daemon :anchor-server:bootJar -x test \
    && cp anchor-server/build/libs/anchor-server-*.jar /tmp/anchor-server.jar

# --- Runtime stage ------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a non-root user — defence in depth, and required by most managed
# container platforms (Kubernetes PSS restricted, ECS hardened, etc).
RUN groupadd --system --gid 10001 anchor \
    && useradd  --system --uid 10001 --gid anchor --home-dir /app --shell /usr/sbin/nologin anchor \
    && mkdir -p /app/uploads && chown -R anchor:anchor /app

COPY --from=build --chown=anchor:anchor /tmp/anchor-server.jar /app/anchor-server.jar

USER anchor

# Defaults match docker-compose.yml; override at runtime as needed.
ENV ANCHOR_PORT=8090 \
    ANCHOR_UPLOAD_DIR=/app/uploads \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

EXPOSE 8090

# Spring Boot Actuator surfaces /actuator/health regardless of the app token.
# wget is in the temurin base image so no extra apt-get needed.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=4 \
    CMD wget -q -O- http://localhost:${ANCHOR_PORT}/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/anchor-server.jar"]
