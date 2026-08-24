# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-noble@sha256:d4920d49e0d7163a1a1534601b733c6e1b37bd53b144d68a51f00382410c7257 AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY core ./core
COPY api ./api
COPY worker ./worker

RUN --mount=type=cache,target=/root/.gradle sed -i 's/\r$//' gradlew \
    && chmod 0755 gradlew \
    && ./gradlew --no-daemon --stacktrace :api:bootJar \
    && jar_file="$(find api/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "${jar_file}" \
    && cp "${jar_file}" /workspace/application.jar \
    && java -Djarmode=tools -jar /workspace/application.jar extract --layers --destination /workspace/extracted

FROM bellsoft/liberica-runtime-container:jre-25-glibc@sha256:a565bbf80f20fdc48b95347e7b527a055e99e11fe7c70ed1e6bba4829cdc29a9

ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="MemoryOS API" \
      org.opencontainers.image.source="https://github.com/dathip04/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

RUN addgroup -S -g 1654 memoryos \
    && adduser -S -D -H -u 1654 -G memoryos memoryos

WORKDIR /application

COPY --from=build --chown=1654:1654 /workspace/extracted/dependencies/ ./
COPY --from=build --chown=1654:1654 /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=1654:1654 /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=1654:1654 /workspace/extracted/application/ ./

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=20 -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

USER 1654:1654
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
