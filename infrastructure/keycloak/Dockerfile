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
ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:26.7.0@sha256:0f198be292568439d700cdbfb893e69a6009bb43a94a06a945b1d3d506c76b13

FROM ${KEYCLOAK_IMAGE} AS build
ENV KC_DB=postgres \
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
