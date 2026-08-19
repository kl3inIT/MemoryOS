# MEM-13 design: production web foundation and owner shell

## Outcome

MemoryOS gains one production-deployable browser application that reuses the Spring Boot Authorization Code + S256 PKCE flow and `HttpOnly` JDBC session delivered by MEM-8. A provisioned owner lands in a real authenticated shell whose identity comes from `GET /api/identity/me`; an unauthenticated browser can initiate login; an unauthorized identity receives an accessible `ACCESS_NOT_PROVISIONED` state.

This increment proves one web path only:

```text
same HTTPS origin
  -> static React application
  -> Spring-owned /oauth2/authorization/memoryos
  -> Keycloak Authorization Code + S256 callback
  -> ActorId-only JDBC session
  -> GET /api/identity/me
  -> authenticated owner shell
```

No provider access, refresh, or ID token reaches browser JavaScript or browser storage.

## Accepted application boundary

`web/` is a root-level deployable, not a Gradle module and not a reusable UI package. The initial repository remains deliberately flat:

```text
api/       Spring Boot HTTP and security composition
core/      capability implementations
worker/    background composition
web/       React browser application
```

The web application is scaffolded from the official Vite React TypeScript template. TanStack Router is added through its official Vite plugin; TanStack Start is not used. Spring already owns HTTP application behavior, OIDC, authorization, sessions, and API composition. A second server/BFF would duplicate runtime and security concerns without an SSR, SEO, or server-function requirement.

Reconsider a server-rendered frontend only after a stable framework release and a measured public-SEO, full-document SSR, or frontend-BFF requirement.

## Frontend stack

- React 19 with strict TypeScript.
- Vite 8 and the official React plugin.
- TanStack Router with file-based routes and generated route tree.
- TanStack Query for remote state and explicit retry policy.
- Tailwind CSS with official shadcn registry primitives and a Magic UI grid-pattern primitive; no speculative shared package or handwritten primitive replacement.
- Root `openapi.yml` with `@hey-api/openapi-ts` Fetch, TypeScript, SDK, and TanStack Query plugins.
- Vitest and Testing Library for route/auth behavior.
- Playwright for the real browser boundary.
- Oxlint and deterministic formatting/type checks.
- pnpm through Corepack with an exact lockfile and declared package manager.

Generated API code is isolated under `web/src/lib/hey-api/`. Product code imports its public generated surface and never edits generated files.

## API contract ownership

Root `openapi.yml` is the backend-owned browser-consumed contract. The first contract contains the existing `GET /api/identity/me` operation and exact `actorId` UUID response. Hey API generates TypeScript types, SDK functions, and TanStack Query options from this artifact.

The generated output is committed. `pnpm check:api` regenerates it and fails when the working tree changes, making API drift visible in CI. Frontend DTOs are not handwritten.

`GET /api/identity/me` accepts either:

- a valid bound bearer identity, preserving the existing API contract; or
- an already-authenticated `ActorSessionAuthenticationToken` loaded from the JDBC-backed browser session.

Bearer authentication remains request-scoped and is never saved into the HTTP session. Other `/api/**` endpoints remain stateless bearer surfaces. The dual-mode exact endpoint is isolated in its own higher-priority security chain so browser-session support does not silently broaden the API boundary.

## Browser state model

The application has four observable states:

1. **Checking session** — the identity request is pending; content exposes a non-disruptive accessible status.
2. **Authenticated** — the exact `ActorId` populates the owner shell.
3. **Unauthenticated** — a `401` renders a direct sign-in gate, not a marketing landing page, with one real action to `/oauth2/authorization/memoryos`.
4. **Unavailable** — network/server failure offers a bounded retry without pretending the user is logged out.

`/access-not-provisioned` is a dedicated public route. It explains that the external identity lacks active MemoryOS authority and offers login retry; it does not expose provider or membership internals.

The frontend does not infer authorization from cached local values. TanStack Query owns only the server response, and page refresh revalidates the server session.

## Product shell and visual direction

The browser UI uses one monochrome enterprise system across every state: Geist typography, semantic white and near-black surfaces, neutral borders and muted text, compact radii, and restrained elevation. The signed-out route remains a centered authentication gate with one near-black action and no card, marketing copy, navigation, or decorative backdrop. Focus remains visible through the neutral ring tokens rather than a branded accent.

The authenticated owner shell uses a compact product bar, a quiet neutral workspace canvas, a near-black identity panel, and a white session panel. It shows only observed state: MemoryOS brand, active-session status, owner role, same-origin transport, and the authenticated actor identifier. It has no warm paper or colored status accents, fake metrics, placeholder memories, disabled feature navigation, or dead controls.

The not-provisioned, unavailable, route-error, not-found, and loading states reuse the same monochrome tokens and compact sans-serif hierarchy. No state introduces a separate visual language.

Responsive behavior starts mobile-first. Keyboard focus, readable contrast, reduced motion, status announcements, slow-loading behavior, and error recovery are first-class states.

## Same-origin runtime

The production web image is a multi-stage build that emits immutable static assets and serves them with unprivileged Nginx. The final container:

- runs non-root;
- supports a read-only root filesystem with bounded temporary paths;
- drops all Linux capabilities and enables `no-new-privileges`;
- exposes a terminating health endpoint;
- uses history fallback for browser routes;
- never applies SPA fallback to `/api`, `/oauth2`, `/login/oauth2`, `/logout`, or `/actuator`.

The existing external reverse proxy sends the complete MemoryOS HTTPS origin to `memoryos-web`. Its Nginx runtime serves the SPA and proxies backend-owned paths to `memoryos-api` on `shared-infra`. Local Vite development proxies the same paths, preserves the browser host and forwarded scheme, and removes only the production `Secure` cookie attribute at its loopback-only HTTP boundary so the OAuth state session survives the callback. Production never performs this rewrite. CORS is not introduced.

## Boundaries retained from production references

- OrgMemory: Vite/React/TanStack/Hey API composition, feature boundaries, generated-client drift gate, browser tests, and static Nginx deployment.
- Airbyte: a centered, single-purpose login stack with a compact heading, full-width identity-provider actions, and no marketing hero ([live login](https://cloud.airbyte.com/login), [source](https://github.com/airbytehq/airbyte-platform/blob/main/airbyte-webapp/src/cloud/views/auth/LoginPage/LoginPage.tsx)).
- Camunda: a quiet full-page canvas with a focused sign-in surface, product identity, and restrained supporting copy ([documented login screenshot](https://docs.camunda.io/assets/images/login-268a4863bad25d9f9ba9dfd2af59fe16.png)).
- Kestra: backend-generated OpenAPI as a build input, deterministic SDK freshness, separated quality gates, and packages only after a real independent lifecycle exists.
- Onyx: explicit authentication service/hook/component boundaries, colocated behavior tests, accessible asynchronous states, and browser-level product verification.

MemoryOS does not copy Kestra's Vue packages, custom generator plugin, module federation, or early design-system split; it does not copy Onyx's Next.js server or handwritten API approach.

## Verification boundary

Completion requires:

- generated-client freshness;
- frontend lint, typecheck, unit tests, and production build;
- backend bearer and browser-session identity tests;
- deterministic Playwright coverage of signed-out, authenticated, and not-provisioned UI boundaries plus browser visual verification at desktop and mobile widths;
- a production image/configuration smoke test;
- IDE inspection of every changed Java, Kotlin DSL, YAML, properties, and XML file;
- repository `clean check` plus the frontend gates.

## Exclusions

No public marketing site, SSR, TanStack Start, frontend BFF, invitation onboarding, Organization switcher, connector UI, memory list, chat UI, speculative dashboard, analytics product, generic design-system package, or token-based browser authentication is added.