# MemoryOS Keycloak: the upstream server, optimized for PostgreSQL, with the MemoryOS login theme.
# Build context: infrastructure/keycloak.
#
# The theme is part of the image rather than a folder mounted from the release. A release directory
# is readable by root only, and Keycloak runs as uid 1000; a mounted theme it cannot read is not an
# error, it is a silent fall-back to the built-in look. Built into the image, the theme ships and
# rolls back with the release that carries it.
#
# The build-time options are the ones the image this replaces was built with. Health must stay on:
# the Compose health check reads /health/ready on the management port.
#
# MEM-114: `cimd` lets an MCP client such as Claude identify itself by a Client ID Metadata Document
# instead of registering a client per connection. Features are build-time options: an optimized
# start ignores a feature that was not built in, so it is set here and not in Compose.
#
# ChatGPT's metadata document carries a property Keycloak 26.8 rejects (keycloak/keycloak#51236), so the
# image adds providers/memoryos-cimd: the built-in executor with the upstream fix's lenient parsing,
# compiled against this very Keycloak's jars. Drop it once a Keycloak release carries keycloak/keycloak#51235.
ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:26.8.0@sha256:b0f60d489d51c5d113390bdf5461d4c06e6051be026c05549f2e1e10ec352bcc
ARG JDK_IMAGE=eclipse-temurin:21-jdk-noble@sha256:1afee26c547e1aa89f47404854dd09203b8bd35a2be269e16bdfd69c4a2c2b50

FROM ${KEYCLOAK_IMAGE} AS keycloak

FROM ${JDK_IMAGE} AS cimd-provider
COPY --from=keycloak /opt/keycloak/lib/lib /keycloak-lib
COPY providers/memoryos-cimd /provider
RUN mkdir /classes && \
    javac --release 21 -Xlint:all,-classfile,-path,-processing -Werror -cp "/keycloak-lib/boot/*:/keycloak-lib/main/*" -d /classes \
      $(find /provider/src/main/java -name '*.java') && \
    cp -R /provider/src/main/resources/. /classes/ && \
    jar --create --file /memoryos-cimd.jar -C /classes .

FROM keycloak AS build
ENV KC_DB=postgres \
    KC_FEATURES=cimd \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true
COPY --from=cimd-provider /memoryos-cimd.jar /opt/keycloak/providers/memoryos-cimd.jar
RUN /opt/keycloak/bin/kc.sh build

FROM keycloak
COPY --from=build /opt/keycloak/ /opt/keycloak/
COPY --chown=keycloak:keycloak themes/memoryos /opt/keycloak/themes/memoryos
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="MemoryOS Keycloak" \
      org.opencontainers.image.source="https://github.com/kl3inIT/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"
USER keycloak
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
