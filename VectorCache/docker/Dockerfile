# ─────────────────────────────────────────────────────────────────
# Stage 1 — Build
# Uses the official Maven image so CI runners don't need Maven installed.
# ─────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy pom first — Docker layer-caches the dependency download step.
# Re-downloading only happens when pom.xml changes, not source files.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build.  -DskipTests skips the exec:java test runner;
# run tests in CI before building the image if you prefer.
COPY src ./src
RUN mvn package -DskipTests -q

# ─────────────────────────────────────────────────────────────────
# Stage 2 — Runtime
# Minimal JRE image — no compiler, no Maven, no source files.
# ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Non-root user for security — running as root inside containers is bad practice
RUN addgroup -S vcache && adduser -S vcache -G vcache

WORKDIR /app

# Copy only the fat-JAR from the builder stage
COPY --from=builder /build/target/vectorcache-*-server.jar app.jar

# Default log directory — mount a volume here for persistent access logs
RUN mkdir -p /app/logs && chown vcache:vcache /app/logs
VOLUME ["/app/logs"]

USER vcache

# ─── Runtime tunables via environment variables ───────────────────
# PORT        — HTTP port (default 8080)
# CAPACITY    — max cache entries (default 10000)
# MAX_MEM_MB  — memory ceiling in MB (default 256)
# TTL_MS      — entry TTL in milliseconds (default 0 = no expiry)
# PARALLELISM — search thread count (default: CPU count)
# LOG_PATH    — access log path inside the container

ENV PORT=8080 \
    CAPACITY=10000 \
    MAX_MEM_MB=256 \
    TTL_MS=0 \
    PARALLELISM=0 \
    LOG_PATH=/app/logs/access.log

EXPOSE ${PORT}

# Health-check: poll /health every 30s, fail after 3 consecutive misses
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:${PORT}/health || exit 1

# Entrypoint passes the port as the first argument; VectorCacheServer.main()
# reads args[0] as the port.
ENTRYPOINT ["sh", "-c", \
  "exec java \
     -XX:+UseContainerSupport \
     -XX:MaxRAMPercentage=75.0 \
     -XX:+ExitOnOutOfMemoryError \
     -Djava.security.egd=file:/dev/./urandom \
     -jar /app/app.jar ${PORT}"]
