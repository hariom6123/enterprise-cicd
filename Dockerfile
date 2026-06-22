# syntax=docker/dockerfile:1.7
# ====================================
# Multi-stage build for Spring Boot (distroless runtime)
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
# distroless/java17-debian12:nonroot ships:
#   - JRE 17 (no JDK, no shell, no package manager)
#   - /usr/bin/tini-static for proper PID 1 signal handling
#   - non-root user named `nonroot` (UID 65532)
# No HEALTHCHECK here — K8s probes handle that (see Helm chart values).
FROM gcr.io/distroless/java17-debian12:nonroot

# Metadata
LABEL org.opencontainers.image.title="Spring Boot App" \
      org.opencontainers.image.description="Enterprise Spring Boot Application" \
      org.opencontainers.image.source="https://github.com/org/spring-boot-app" \
      org.opencontainers.image.licenses="Proprietary"

WORKDIR /app

# Copy Spring Boot layers in optimal order
COPY --from=builder --chown=nonroot:nonroot /build/extracted/dependencies/ ./
COPY --from=builder --chown=nonroot:nonroot /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=nonroot:nonroot /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=nonroot:nonroot /build/extracted/application/ ./

# /tmp writable for heap dumps (distroless ships /tmp but it's empty)
# nonroot already owns it, but be explicit:
# (skip — default /tmp is fine and owned by nonroot in :nonroot variant)

USER nonroot

EXPOSE 8080

# Use tini-static for proper PID 1 signal handling (bundled in this image tag).
# ENTRYPOINT + CMD use exec form (no shell — distroless has none).
# JAVA_TOOL_OPTIONS is read by the JVM itself, so flags can still be overridden
# at run time via -e JAVA_TOOL_OPTIONS=... without needing a shell.
ENTRYPOINT ["/usr/bin/tini-static", "--"]

ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC \
                       -XX:MaxRAMPercentage=75.0 \
                       -XX:+ExitOnOutOfMemoryError \
                       -XX:+HeapDumpOnOutOfMemoryError \
                       -XX:HeapDumpPath=/tmp/heapdumps \
                       -Djava.security.egd=file:/dev/./urandom \
                       -Dspring.profiles.active=production"

CMD ["org.springframework.boot.loader.launch.JarLauncher"]