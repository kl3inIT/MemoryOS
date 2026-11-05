# The OpenSandbox lifecycle server for the Chat sandbox (MEM-270), with MemoryOS's configuration, as
# infrastructure/keycloak/Dockerfile carries Keycloak's.
FROM opensandbox/server:release-1.2.0@sha256:25bebdcf1cf7724eb923faecbdbbf82d4038c6ab35c736151574726d14006f30

ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="MemoryOS Sandbox" \
      org.opencontainers.image.source="https://github.com/kl3inIT/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

COPY config.toml /etc/opensandbox/config.toml
COPY --chmod=0755 start.sh /usr/local/bin/memoryos-sandbox-start

ENTRYPOINT ["/usr/local/bin/memoryos-sandbox-start"]
CMD []
