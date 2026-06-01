# ============================================================
# Stage 1 — Build
# Compiles the application. This layer is discarded in the
# final image — the JDK never ships to production.
# ============================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and pom.xml first.
# Docker layer caching: if pom.xml hasn't changed, the
# dependency download step is skipped on subsequent builds.
COPY .mvn/  .mvn/
COPY mvnw   pom.xml ./
RUN  ./mvnw dependency:go-offline -q

# Copy source and compile.
COPY src/ src/
RUN  ./mvnw clean package -DskipTests -q

# ============================================================
# Stage 2 — Runtime
# Only the JRE + the compiled JAR. ~200MB vs ~700MB with JDK.
# ============================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# OCI image labels — visible in GitHub Packages
LABEL org.opencontainers.image.source="https://github.com/PubuduGunasekara/distributed-task-scheduler"
LABEL org.opencontainers.image.description="Production-grade distributed task scheduler"
LABEL org.opencontainers.image.licenses="MIT"

# Non-root user — never run application containers as root.
# If the process is compromised, the attacker has no root access.
RUN addgroup -S scheduler && adduser -S scheduler -G scheduler
USER scheduler

WORKDIR /app

COPY --from=builder /build/target/distributed-task-scheduler-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

# JVM flags for containerized environments:
#   UseContainerSupport   — reads cgroup limits (not host RAM)
#   MaxRAMPercentage=75   — uses 75% of container memory for heap
# Without these, the JVM defaults to host memory and may OOM.
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-jar", "app.jar"]