# Multi-stage Distroless Non-Root Dockerfile for SafePay reconciliation
# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy Gradle build files first for layer caching
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY common ./common
COPY reconciliation ./reconciliation

# Build fat bootJar without running tests
RUN chmod +x ./gradlew && ./gradlew :reconciliation:bootJar --no-daemon -x test

# Stage 2: Distroless Non-Root Runtime
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app

# Copy artifact from builder stage
COPY --from=builder /workspace/reconciliation/build/libs/reconciliation-*.jar app.jar

# Enforce hardware memory constraints & serial GC for low RAM footprint
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m"
ENV SPRING_PROFILES_ACTIVE="docker"

# Non-root user (distroless UID: 65532)
USER 65532:65532

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
