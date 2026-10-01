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
ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:26.8.0@sha256:b0f60d489d51c5d113390bdf5461d4c06e6051be026c05549f2e1e10ec352bcc

FROM ${KEYCLOAK_IMAGE} AS build
ENV KC_DB=postgres \
    KC_FEATURES=cimd \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

FROM ${KEYCLOAK_IMAGE}
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
