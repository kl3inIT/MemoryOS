# syntax=docker/dockerfile:1.7

FROM node:26-alpine@sha256:aadf416b2cdce311a8811ba3f0608a61b77dbf997500e2eafe781b51f6a0b019 AS build

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

FROM nginx:1.30-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46

ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="MemoryOS Web" \
      org.opencontainers.image.source="https://github.com/dathip04/MemoryOS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

RUN rm -f /etc/nginx/conf.d/default.conf

COPY --chown=101:101 web/nginx.conf /etc/nginx/nginx.conf
COPY --from=build --chown=101:101 /workspace/web/dist /usr/share/nginx/html

USER 101:101
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
