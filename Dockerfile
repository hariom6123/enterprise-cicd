# syntax=docker/dockerfile:1.7
# ====================================
# Multi-stage build for Spring Boot
# ====================================

# ---- Build Stage ----
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /build

# Copy Maven files first (better caching)
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw ./

# Download dependencies (cached layer)
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw dependency:go-offline -B -ntp

# Copy source and build
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp clean package -DskipTests \
    -Dmaven.javadoc.skip=true

# Extract layers for better Docker caching (Spring Boot 2.3+)
RUN mkdir -p extracted && \
    java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# ---- Runtime Stage ----
FROM eclipse-temurin:17-jre-jammy AS runtime

# Metadata
LABEL org.opencontainers.image.title="Spring Boot App" \
      org.opencontainers.image.description="Enterprise Spring Boot Application" \
      org.opencontainers.image.source="https://github.com/org/spring-boot-app" \
      org.opencontainers.image.licenses="Proprietary"

# Install security updates and required tools
RUN apt-get update && \
    apt-get upgrade -y && \
    apt-get install -y --no-install-recommends \
        curl \
        tini \
        dumb-init && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -g 10001 spring && \
    useradd -u 10001 -g 10001 -s /bin/bash -m spring

WORKDIR /app

# Copy Spring Boot layers (in optimal order)
COPY --from=builder --chown=spring:spring /build/extracted/dependencies/ ./
COPY --from=builder --chown=spring:spring /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:spring /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /build/extracted/application/ ./

# Create tmp directories (since readOnlyRootFilesystem)
RUN mkdir -p /tmp/heapdumps /app/logs && \
    chown -R spring:spring /tmp/heapdumps /app/logs

USER 10001:10001

EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1

# Use tini for proper signal handling
ENTRYPOINT ["/usr/bin/tini", "--"]

# JVM options
ENV JAVA_OPTS="-XX:+UseG1GC \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+ExitOnOutOfMemoryError \
               -XX:+HeapDumpOnOutOfMemoryError \
               -XX:HeapDumpPath=/tmp/heapdumps \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.profiles.active=production"

CMD ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]