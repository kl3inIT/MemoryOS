# The OpenSandbox lifecycle server for the Chat sandbox (MEM-270), with MemoryOS's configuration, as
# infrastructure/keycloak/Dockerfile carries Keycloak's.
FROM opensandbox/server:release-1.1.1@sha256:bcd5f264101262cebfa7e1ff9cd0424403e42f31a6596e4364cf90d8cb5c936b

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
