# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-noble@sha256:534968c051301957beae735e7ba1db54d99ddecf08746d3b9d4f318cc132dbc3 AS build

ARG INFISICAL_CLI_VERSION=0.43.125
ADD --checksum=sha256:8c3431afab5097ca7d943585be1580ebc13c28843e7d0c5292fb07d077be0372 \
    https://github.com/Infisical/cli/releases/download/v${INFISICAL_CLI_VERSION}/cli_${INFISICAL_CLI_VERSION}_linux_amd64.tar.gz \
    /tmp/infisical-cli.tar.gz
RUN tar -xzf /tmp/infisical-cli.tar.gz -C /usr/local/bin infisical \
    && /usr/local/bin/infisical --version
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY core ./core
COPY connector ./connector
COPY api ./api
COPY worker ./worker

RUN --mount=type=cache,target=/root/.gradle sed -i 's/\r$//' gradlew \
    && chmod 0755 gradlew \
    && ./gradlew --no-daemon --stacktrace :api:bootJar :worker:bootJar \
    && api_jar="$(find api/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && worker_jar="$(find worker/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "${api_jar}" \
    && test -n "${worker_jar}" \
    && cp "${api_jar}" /workspace/api.jar \
    && cp "${worker_jar}" /workspace/worker.jar \
    && java -Djarmode=tools -jar /workspace/api.jar extract --layers --destination /workspace/api-extracted \
    && java -Djarmode=tools -jar /workspace/worker.jar extract --layers --destination /workspace/worker-extracted

FROM bellsoft/liberica-runtime-container:jre-25-glibc@sha256:f4273aca6e32b3da7440b3776238116b3f1db7b85060c12848cc24048032207c AS runtime

RUN addgroup -S -g 1654 memoryos \
    && adduser -S -D -H -u 1654 -G memoryos memoryos

WORKDIR /application
COPY --from=build /usr/local/bin/infisical /usr/local/bin/infisical
COPY --chmod=0755 api/src/main/docker/api-entrypoint.sh /usr/local/bin/memoryos-entrypoint
COPY --chmod=0755 api/src/main/docker/application-launcher.sh /usr/local/bin/memoryos-launcher

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=20 -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

USER 0:0
ENTRYPOINT ["/usr/local/bin/memoryos-entrypoint"]

FROM runtime AS worker
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="MemoryOS Worker" \
      org.opencontainers.image.source="https://github.com/dathip04/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"
ENV MEMORYOS_APPLICATION_JAR=worker.jar \
    MEMORYOS_EXTRACTION_CLASSPATH="/application/worker.jar:/application/lib/*"
COPY --from=build --chown=1654:1654 /workspace/worker-extracted/dependencies/ ./
COPY --from=build --chown=1654:1654 /workspace/worker-extracted/spring-boot-loader/ ./
COPY --from=build --chown=1654:1654 /workspace/worker-extracted/snapshot-dependencies/ ./
COPY --from=build --chown=1654:1654 /workspace/worker-extracted/application/ ./

FROM runtime AS api
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="MemoryOS API" \
      org.opencontainers.image.source="https://github.com/dathip04/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"
ENV MEMORYOS_APPLICATION_JAR=api.jar
COPY --from=build --chown=1654:1654 /workspace/api-extracted/dependencies/ ./
COPY --from=build --chown=1654:1654 /workspace/api-extracted/spring-boot-loader/ ./
COPY --from=build --chown=1654:1654 /workspace/api-extracted/snapshot-dependencies/ ./
COPY --from=build --chown=1654:1654 /workspace/api-extracted/application/ ./
