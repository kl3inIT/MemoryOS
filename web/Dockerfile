# syntax=docker/dockerfile:1.7

FROM node:24-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43 AS build

WORKDIR /workspace

RUN corepack enable \
    && corepack install --global pnpm@11.22.0

COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml ./web/
WORKDIR /workspace/web
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile

WORKDIR /workspace
COPY web ./web
COPY openapi.yml ./openapi.yml
WORKDIR /workspace/web
RUN pnpm generate:api \
    && pnpm build

FROM nginx:1.31-alpine@sha256:db35bfc6b2951e7f8a72db5db120288c127ffaeeb4a6d4b95a26fead017d5913

ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="MemoryOS Web" \
      org.opencontainers.image.source="https://github.com/dathip04/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

RUN rm -f /etc/nginx/conf.d/default.conf

COPY --chown=101:101 web/nginx.conf /etc/nginx/nginx.conf.template
COPY --from=build --chown=101:101 /workspace/web/dist /usr/share/nginx/html
ENV MEMORYOS_OBJECT_STORAGE_CONNECT_SRC=""


USER 101:101
EXPOSE 8080
ENTRYPOINT ["/bin/sh", "-c", "envsubst '$MEMORYOS_OBJECT_STORAGE_CONNECT_SRC' < /etc/nginx/nginx.conf.template > /tmp/nginx.conf && exec nginx -c /tmp/nginx.conf -g 'daemon off;'"]
