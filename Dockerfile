# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-noble@sha256:534968c051301957beae735e7ba1db54d99ddecf08746d3b9d4f318cc132dbc3 AS build

WORKDIR /workspace

# Dependencies live in an image layer (not a cache mount) so CI's BuildKit cache can restore them.
ENV GRADLE_USER_HOME=/workspace/.gradle-home
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY build-logic ./build-logic
COPY memoryos-core/build.gradle.kts ./memoryos-core/
COPY sources/build.gradle.kts ./sources/
COPY memoryos-api/build.gradle.kts ./memoryos-api/
COPY memoryos-worker/build.gradle.kts ./memoryos-worker/
RUN sed -i 's/\r$//' gradlew \
    && chmod 0755 gradlew \
    && ./gradlew --no-daemon --no-configuration-cache --stacktrace resolveDependencies

COPY memoryos-core ./memoryos-core
COPY sources ./sources
COPY memoryos-api ./memoryos-api
COPY memoryos-worker ./memoryos-worker

RUN ./gradlew --no-daemon --stacktrace :memoryos-api:bootJar :memoryos-worker:bootJar \
    && api_jar="$(find memoryos-api/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && worker_jar="$(find memoryos-worker/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
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
COPY --chmod=0755 memoryos-api/src/main/docker/api-entrypoint.sh /usr/local/bin/memoryos-entrypoint
COPY --chmod=0755 memoryos-api/src/main/docker/application-launcher.sh /usr/local/bin/memoryos-launcher

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=20 -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

USER 0:0
ENTRYPOINT ["/usr/local/bin/memoryos-entrypoint"]

FROM runtime AS worker
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="MemoryOS Worker" \
      org.opencontainers.image.source="https://github.com/kl3inIT/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"
# MEM-244: the Worker shares its container's memory limit with the Tika child JVMs it starts, so its heap is half the
# limit, not the runtime's 70%, and starts small. The collector is named: below about 1.75 GB the JVM picks SerialGC on
# its own and above it G1, so a changed limit would change the collector unnoticed; measured at 1 GiB, SerialGC started
# faster and held less memory than G1. The child JVMs inherit these options.
ENV MEMORYOS_APPLICATION_JAR=worker.jar \
    MEMORYOS_EXTRACTION_CLASSPATH="/application/worker.jar:/application/lib/*" \
    JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=5 -XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
COPY --from=build --chown=1654:1654 /workspace/worker-extracted/dependencies/ ./
COPY --from=build --chown=1654:1654 /workspace/worker-extracted/spring-boot-loader/ ./
COPY --from=build --chown=1654:1654 /workspace/worker-extracted/snapshot-dependencies/ ./
COPY --from=build --chown=1654:1654 /workspace/worker-extracted/application/ ./

FROM runtime AS api
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="MemoryOS API" \
      org.opencontainers.image.source="https://github.com/kl3inIT/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      io.memoryos.chat.tokenizer-profiles="openai-o200k-v1"
# MEM-137: the Azure Speech SDK's native layer needs these even when audio flows through in-memory streams. The JVM
# starts without them and the first Azure session fails, so the build asserts they are present. The worker loads no SDK.
RUN apk add --no-cache libstdc++ libuuid openssl ca-certificates alsa-lib \
    && test -e /usr/lib/libstdc++.so.6 \
    && test -e /usr/lib/libuuid.so.1
ENV MEMORYOS_APPLICATION_JAR=api.jar \
    JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=20 -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED" \
    OPT_OUT_TRACKING=true
COPY --from=build --chown=1654:1654 /workspace/api-extracted/dependencies/ ./
COPY --from=build --chown=1654:1654 /workspace/api-extracted/spring-boot-loader/ ./
COPY --from=build --chown=1654:1654 /workspace/api-extracted/snapshot-dependencies/ ./
COPY --from=build --chown=1654:1654 /workspace/api-extracted/application/ ./
