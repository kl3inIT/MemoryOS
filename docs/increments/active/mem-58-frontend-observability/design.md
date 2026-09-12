# MEM-58 — Frontend error monitoring and trace correlation

## Current decision

The original research compared self-hosted Grafana Faro/Alloy with self-hosted
Sentry. The selected first implementation is **Sentry Cloud error monitoring**
for the React browser application. This changes the original self-hosted
assumption and must be reflected in the Linear issue before staging rollout.

The initial capture boundary is deliberately application-wide: initialise the
SDK before React renders and report uncaught React render failures through the
existing root ErrorBoundary. A small, explicit set of handled system workflow
failures is also reported: FILE upload, Google Drive synchronization, indexing,
and Search. The normal UI/API error paths remain authoritative; telemetry is
additional only. Telemetry is optional: failure to initialise or send telemetry
must not affect React rendering or API behaviour.

## Local configuration

Vite exposes only variables prefixed with `VITE_` to browser code:

| Variable | Meaning | Secret |
| --- | --- | --- |
| `VITE_MEMORYOS_SENTRY_DSN` | Browser event-ingestion address | No; it is shipped to the browser |
| `VITE_MEMORYOS_SENTRY_ENVIRONMENT` | `local`, `staging`, or `production` | No |
| `VITE_MEMORYOS_RELEASE` | Release identity, eventually the Git SHA | No |

`web/.env.local` is ignored and is for local Vite only. A staging image is a
static Vite bundle, so it cannot consume `VITE_*` values after it has been
built. The web Nginx entrypoint writes a non-cacheable `/runtime-config.js`
under its existing `/tmp` mount from `MEMORYOS_SENTRY_DSN`,
`MEMORYOS_SENTRY_ENVIRONMENT`, and the existing `MEMORYOS_RELEASE`. The static
image accepts `MEMORYOS_SENTRY_REPLAY_ENABLED`. It is enabled for local,
staging, and production at the assignee's request; all text and input values
must be masked and media must be blocked. Request bodies, console capture, and
user PII remain disabled.
image stays immutable; do not copy local environment files to the server.

## Privacy boundary

The client removes `event.request`, `event.user`, and all breadcrumbs before
events leave the browser. Product text, document content, prompts, cookies,
authorization values, request bodies, Source IDs, document IDs, filenames, and
search queries are out of scope for telemetry. Workflow capture uses only the
allowlisted `workflow`, `stage`, `failure_kind`, and optional HTTP status tags.
It does not report expected validation, authentication, authorization, conflict,
or not-found responses. The frontend does not add per-component ErrorBoundaries.

## Follow-up acceptance

Before staging: prove a deliberately triggered production-minified error has
the intended environment/release and no prohibited payload. Source maps require
a CI-only upload token and private artifact handling. Browser-to-API W3C
`traceparent` correlation is a separate spike: Sentry Cloud must not be assumed
to satisfy the existing Tempo acceptance without a demonstrated shared trace ID.
