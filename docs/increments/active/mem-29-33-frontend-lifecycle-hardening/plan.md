# MEM-29–33 implementation plan: frontend lifecycle hardening

## Scope and contracts

- [x] Audit TanStack Router, Query, Table, Radix, React, Hey API, and Vite integration against current framework guidance and deployed behavior.
- [x] Record the five evidence-backed defects as MEM-29 through MEM-33 with priorities and dependency relations.
- [x] Define one coherent router/layout/session design and explicit exclusions before implementation.
- [x] Add this active increment to the repository roadmap.

## Router and layouts

- [x] Replace every application-owned raw internal anchor with a typed TanStack Router link while preserving native OAuth2, continuation, mail, logout, and fragment navigation.
- [x] Introduce a pathless authenticated layout and nested administration layout without changing public URLs.
- [x] Move the administration shell and capability gate to the layout so child pages never mount for unauthorized actors.
- [x] Keep administration shell state across child navigation and close controlled mobile navigation after route changes.
- [x] Remove obsolete direct-root route files and regenerate the committed route tree.

## Session/query convergence

- [x] Move the accepted actor/authority fingerprint from component state to QueryClient-owned state.
- [x] Purge non-identity queries and mutations before accepting a changed actor or authority projection.
- [x] Refetch identity whenever the browser returns to the foreground.
- [x] Converge identity, private-query, and private-mutation `401` responses through one cache/session invalidation path.
- [x] Preserve local behavior for non-authentication failures.

## Production interactions

- [x] Give invitation creation one semantic form, synchronous single-flight guard, and explicit non-submit button types.
- [x] Emit font assets as same-origin files and add a production-output assertion against `data:font`.

## Verification and delivery

- [x] Add focused unit coverage for persistent session fingerprints, authority changes, and `401` convergence, plus observable browser coverage for duplicate submission.
- [x] Add Playwright coverage for same-document routing, identity request stability, persistent admin shell state, mobile drawer closure, and single invitation POST.
- [x] Inspect every edited IDE-supported file with warnings enabled and run frontend formatting, linting, type, unit, route-generation, and build gates.
- [x] Run `gradlew.bat clean check --no-daemon` and the complete Playwright suite.
- [x] Exercise the changed browser surfaces and record exact runtime evidence.
- [x] Consolidate durable facts into architecture, identity/invitation specs, test matrices, and increment verification evidence.
- [ ] Keep the increment active until guarded PR merge, exact main CI, staging smoke, Linear closure, and move to `completed/`.
