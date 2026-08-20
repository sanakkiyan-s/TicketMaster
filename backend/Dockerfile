# Multi-stage build for any of this repo's Spring Boot services. Shared
# rather than one Dockerfile per service, because they are byte-for-byte
# identical except which Gradle module gets built - the build context
# must be the repo ROOT (not backend/<service>/), since Gradle's
# settings.gradle.kts declares all 15 modules and needs the full tree to
# resolve even when only building one.
#
# infra/docker-compose.yml passes MODULE as a build arg per service
# (auth-service, api-gateway, user-service - the only three with real
# sources, ADR-036 Phase 1).

FROM gradle:9.1.0-jdk21-jammy AS build
ARG MODULE
WORKDIR /workspace
COPY . .
# Uses the official Gradle image's own pre-installed Gradle (matching
# gradle-wrapper.properties' pinned 9.1.0) rather than ./gradlew - the
# wrapper's self-download of the distribution zip from
# services.gradle.org reliably timed out from inside this build
# network (10s networkTimeout, consistent failure, not transient
# contention - a bare curl to the same host resolved fine, so this is
# specifically the wrapper's own download path, not a general network
# outage). This sidesteps needing to diagnose that further.
RUN gradle :backend:${MODULE}:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-jammy
ARG MODULE
# curl, not wget: the compose healthcheck below (and dev.sh's shared
# wait_for_health) standardizes on curl across every service, container
# and host-side alike - one probe tool, not two.
RUN apt-get update && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# OTel Java agent (ADR-015): auto-instruments Spring MVC/JDBC/Kafka/
# Lettuce and bridges Micrometer -> OTel spans+metrics from one API, no
# per-service SDK dependency needed. Baked into the shared image rather
# than downloaded per-service, same "one edit point" reasoning as this
# Dockerfile already applies to the JVM/build stage above. Export
# defaults live here since they're identical for every service; only
# OTEL_SERVICE_NAME (must differ per service) is set in
# infra/docker-compose.yml. If otel-collector isn't running (e.g. plain
# `./scripts/dev.sh backend` without the observability profile), the
# agent logs a warning and drops telemetry rather than failing the
# service - safe by the agent's own default behavior, not something this
# Dockerfile has to arrange.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.10.0/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317 \
    OTEL_EXPORTER_OTLP_PROTOCOL=grpc \
    OTEL_TRACES_EXPORTER=otlp \
    OTEL_METRICS_EXPORTER=otlp \
    OTEL_LOGS_EXPORTER=otlp \
    OTEL_RESOURCE_ATTRIBUTES=service.namespace=ticketmaster,deployment.environment=dev

WORKDIR /app
COPY --from=build /workspace/backend/${MODULE}/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-javaagent:/otel/opentelemetry-javaagent.jar", "-jar", "/app/app.jar"]
