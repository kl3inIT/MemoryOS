# MEM-82 — Vadan landing page delivery plan

## Outcome

Ship `https://vadan.app` as a standalone static MemoryOS product site operated independently from the authenticated application. The final implementation, accepted content and visual direction are in [design.md](design.md); measured browser/build evidence is in [verification.md](verification.md).

MEM-82 is delivered through PRs #95 and #103 and is recorded under `completed/`. Deployment health, public DNS/TLS behavior and business content acceptance remain separate evidence and must not be inferred from repository delivery.

## Delivery boundaries

- `landing/` is an independent pnpm/Vite/React package.
- Nginx serves the production build read-only with a strict same-origin CSP.
- CI verifies source, tests, metadata, production build and container smoke.
- Publication produces a digest-addressed landing image outside the MemoryOS application release bundle.
- Operators deploy the separate `memoryos-landing` Compose project behind Nginx Proxy Manager.
- The application deployment and rollback scripts never manage the landing site.
- Public copy contains no internal architecture, delivery-status labels, fabricated metrics or source-repository link.

## Implemented work

- [x] Create the standalone pinned package, TypeScript/build/test/lint/format configuration and local asset policy.
- [x] Define light/dark tokens, Hanken Grotesk typography, responsive layout and reduced-motion behavior.
- [x] Add canonical metadata, JSON-LD, robots, sitemap, favicon and social preview image.
- [x] Keep all public copy and structured lists in one typed content module.
- [x] Implement the approved header, hero, trust strip, product story, capabilities, Organizational AI Memory, process, access, deployment, FAQ and footer sections.
- [x] Apply the accepted 6.0 motion direction and the later type-led simplification without changing the approved positioning boundaries.
- [x] Keep the final static state available when motion is reduced or JavaScript enhancement fails.
- [x] Build the least-privilege Nginx image and verify `/`, `/healthz`, public assets, cache policy, security headers and real 404 behavior.
- [x] Add the independent operator Compose definition and health check.
- [x] Add CI gate and digest publication without adding the landing image to application `images.env`.
- [x] Document deployment, DNS/proxy setup, rollback and external checks in the [landing runbook](../../../runbooks/landing.md).
- [x] Consolidate durable architecture, delivery-matrix, CI/CD and README facts.

## Verification summary

The package gate covers lint, format, unit tests, metadata assertions, production build, font/CSP assets and TypeScript. Browser review covers desktop, tablet and phone layouts, light/dark themes, horizontal overflow, keyboard behavior, motion lifecycle and reduced-motion static behavior. Container verification covers the production Nginx boundary. Lighthouse measurements and the fixes they drove are retained in [verification.md](verification.md).

These checks prove the repository and local production-image contracts. They do not by themselves prove:

- the currently deployed public digest;
- DNS, TLS, HSTS or the `www` redirect;
- the external production Lighthouse result;
- preservation of external mail DNS records;
- final business/content approval.

Record those mutable external results in Linear or the operating record, not by growing this completed implementation plan.

## Canonical references

- [Accepted product and visual design](design.md)
- [Verification evidence](verification.md)
- [Landing deployment runbook](../../../runbooks/landing.md)
- [Delivery verification matrix](../../../tests/delivery.md)
- [CI/CD runbook](../../../runbooks/ci-cd.md)
- [Repository roadmap](../../../roadmap.md)
