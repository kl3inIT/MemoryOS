# MEM-82 — Vanda public landing page

## Problem

Vanda needs a public website at `https://vanda.app` that presents MemoryOS for two uses: the company-website field of the AWS Activate application, and the link sent with the AWS Production PoC infrastructure/credit brief ([MEM-80](https://linear.app/memory-os/issue/MEM-80)). No public page exists. `web/` is the per-customer self-hosted application whose `/` is authenticated Chat, and [MEM-13](../../completed/mem-13-production-web-foundation/design.md) deliberately kept marketing out of it.

Linear: [MEM-82](https://linear.app/memory-os/issue/MEM-82).

## Accepted decisions (2026-09-11)

- Organization **Vanda** (`vanda.app`, contact `aws@vanda.app`); product **MemoryOS** ("MemoryOS by Vanda"); **Backed by GenAI Fund**. English only.
- The page presents the complete product: the problem MemoryOS solves, its full capability set from the Production PoC scope of work, and its direction beyond the PoC. It carries no per-feature delivery-status labels. Delivery status remains in [the roadmap](../../../roadmap.md), not on the marketing page.
- Tasco is named as Vanda's partner in the Production PoC (September–December 2026), never as a customer. No Tasco pricing, internal architecture, users or data appear.
- The site serves the apex `vanda.app` from the existing staging VPS, replacing the current Cloudflare redirect to `roll-bits.com`. `www.vanda.app` redirects to the apex. The Google Workspace MX records stay unchanged.
- Laura, who approves external MEM-80 material, reviews the content before the link is sent to AWS.

## Reference baseline

Open SaaS (`wasp-lang/open-saas`, MIT) at commit `cbd30162b05d798b3a3f955ab5781940b67bec89`, `template/app/src/landing-page/` and `template/app/src/client/components/NavBar/`.

Observed: `LandingPage.tsx` composes Hero → ExamplesCarousel → highlighted feature → FeaturesGrid (bento) → Testimonials → FAQ → Footer. All copy lives in `contentSections.tsx`. `SchemaMarkup.tsx` emits JSON-LD. The NavBar is sticky and shrinks after scrolling, with a Radix Sheet on mobile.

Kept: the section composition, one typed content module, the bento capability grid, the text-plus-visual highlighted feature, FAQ, footer, JSON-LD, and a sticky header.

| Open SaaS | MemoryOS landing | Reason |
| --- | --- | --- |
| Wasp app with routes, auth and payments | Static single-page Vite build, no auth | Only public content is needed; product sign-in stays in `web/` |
| Testimonials, examples carousel, client logos | Trust strip with the Tasco and GenAI Fund logos | No fabricated social proof; the product owner supplied both logos for this page |
| Radix Accordion and Sheet | Native `<details>` for FAQ and mobile menu | Same behavior without a component-library dependency on a static page |
| NavBar that shrinks on scroll | Fixed-height sticky header | No scroll listener for a cosmetic change |
| Default palette, gradients, dark-mode switcher | Navy and brand-blue light and dark tokens with one hero glow, Hanken Grotesk, and a header theme toggle | Product-owner direction: a technical look in the manner of Laravel Cloud, on the product's own token names and typography |
| Screenshot images in the highlighted feature | Markup-built product preview (question → cited answer) | Crisp at every width and needs no screenshot pipeline or customer data |
| JSON-LD rendered by React | Static JSON-LD in `index.html` | Visible to crawlers without JavaScript; data blocks do not execute under the strict CSP |

## Page

1. **Header** — MemoryOS mark, anchors (Product, How it works, Deployment, Roadmap, FAQ), light/dark theme toggle (inside the menu below `md`), Contact action (`mailto:aws@vanda.app`), skip link.
2. **Hero** — the positioning, Contact and "See how it works" actions, product preview.
3. **Trust strip** — the Tasco and GenAI Fund logos with "Partnering with Tasco" and "Backed by GenAI Fund". The logos are single-colour alpha masks (`src/assets/logos/`) cropped from the product owner's files, so they take each theme's content color.
4. **Problem → solution** — two highlighted features: scattered company knowledge becomes one place to search and ask with citations; enterprise AI stays governed through one permission model shared by search, agents and MCP.
5. **Capabilities** (bento) — data connectors; enterprise search; cited answers; Python analysis and reports; enterprise SSO; custom agents; AI asset governance; MCP server; permission-aware retrieval.
6. **How it works** — connect → index → ask → verify, plus the security flow: SSO sign-in, ACL check before retrieval, only permitted context reaches the model.
7. **Deployment on AWS** — the PoC architecture as a responsive markup diagram: application and document-processing EC2, data/search/storage EC2 in a private VPC, S3 and EBS snapshot backups, CloudWatch, KMS and Secrets Manager, IAM, ECR, and a managed model endpoint on AWS. No prices, credit amounts or instance sizes.
8. **Roadmap** — the four monthly PoC milestones from September to December 2026, then the post-PoC direction: organization-wide rollout, more business-system connectors, web search and deep research, and high-availability deployment.
9. **FAQ** and **Footer** — contact call to action, © Vanda, GitHub repository, third-party notices.

## Visual direction

The product owner asked for a technical look in the manner of Laravel Cloud. Hanken Grotesk and the product's token names stay; the values become a navy-and-blue identity: a near-black navy ground in the dark theme, a cool light ground in the light theme, and one light source — a blue glow behind the hero's cited-answer preview, echoed softly behind the closing call to action. The preview stays the one emphasized element, because a verifiable answer is what MemoryOS delivers. Brand blue marks the product's own moving parts: citations, capability icons, the index in the sources diagram, and the markers of real sequences (how it works, request flow, roadmap timeline). Approval green is used only for an approved AI asset. Headings stay left-aligned without labels above them, and capabilities form one hairline-divided grid instead of separately shadowed cards.

Motion, also at the product owner's request, answers the visitor's own actions. Switching the theme grows the new theme in a circle from the toggle (View Transitions API). Scrolling drives everything else through CSS scroll-driven animations, so it follows the scroll in both directions: blocks rise into place as they enter, the how-it-works rules and the roadmap timeline fill as their steps pass, and the hero glow drifts away. The hero's copy, actions and preview enter once on load; the `h1` stays still so it paints as the largest contentful element immediately. In grids the content moves, not the cell, so the hairline grid never shows through. Browsers without scroll-driven animations and visitors who prefer reduced motion get the static page, and no content depends on JavaScript to become visible.

## Technical design

- `landing/` is a standalone pnpm package (own lockfile, not a workspace member of `web/`). It uses the same pinned versions as `web/` for React, Vite, TypeScript, Tailwind, lucide-react, clsx/tailwind-merge, Hanken Grotesk, Vitest, Testing Library, oxlint and oxfmt. There is no router, query client, API client, Radix or class-variance-authority.
- `src/content.ts` owns every string, link and list; section components under `src/sections/` render it. Shared primitives live under `src/components/`: `ActionLink` (default-tone rows of the action matrix as links), `Section` (landmark, heading and description) and `BrandMark`.
- `src/styles/tokens.css` keeps the `web/` semantic token names and default-tone action rows (source: `web/src/styles/tokens.css`) with the landing's own navy/blue values for light and `.dark`, plus `accent`, `glow` and `brand-mark` tokens; `src/styles/base.css` holds the two decorative glow layers. It is a deliberate copy rather than an import: the application and the marketing site deploy independently, and a cross-package import would couple their build contexts. `src/styles/theme.css` maps them for Tailwind and adds landing-only display sizes next to the web typography presets.
- The theme follows the web app's contract: the `dark` class on `<html>` and the `memoryos-theme` storage key (`light` or `dark`; absent means the system preference). `public/theme-init.js`, a classic same-origin script at the top of `<head>`, applies it before first paint, so the strict CSP needs no inline script and dark-mode visitors never see a light flash. `src/lib/theme.ts` owns the toggle, follows system changes until the visitor chooses, and falls back to the system preference when the browser blocks storage. The Open Graph image stays light.
- `index.html` owns the title, description, canonical URL, Open Graph/Twitter tags, and JSON-LD for `Organization` (Vanda, `funder` GenAI Fund), `SoftwareApplication` (MemoryOS) and `WebSite`. `public/` holds `favicon.svg`, `og-image.png` (1200 × 630, rendered from `scripts/og-image.html`), `robots.txt`, `sitemap.xml` and `THIRD_PARTY_NOTICES.txt` crediting Open SaaS and Onyx/Opal.
- The page renders on the client. Prerendering is not added: reviewers use browsers, crawlers that execute JavaScript see the full page, and the static head carries the metadata. Revisit only if search visibility becomes a requirement.

## Delivery

- `landing/Dockerfile` builds from the `landing/` directory as its own context (with `landing/.dockerignore`) using `node:24-alpine`, and serves with the same pinned `nginx:1.31-alpine` as `web/` as UID 101 on port 8080, with OCI revision/source labels. nginx starts directly; the image's entrypoint scripts are not needed.
- `landing/nginx.conf` serves `/`, the hashed `/assets/` and the public files, returns 404 for unknown paths, answers `GET /healthz` for the container check, compresses text assets, and sets a strict CSP (`default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'none'`) plus `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` and `X-Frame-Options`. Hashed assets are immutable; HTML is `no-cache`. HSTS is set at Nginx Proxy Manager, where TLS terminates.
- `infrastructure/deployment/compose.landing.yaml` defines one read-only `landing` service (`memoryos-landing`, `cap_drop: ALL`, `no-new-privileges`, tmpfs, small CPU/memory limits, healthcheck, no host port) on the external `proxy-network`. It runs as Compose project `memoryos-landing`, so the application deployment script never stops, pulls or rolls it back.
- CI adds a `landing` job to `CI Gate`: install, `pnpm --dir landing check`, image build, [`landing/scripts/smoke-image.sh`](../../../../landing/scripts/smoke-image.sh) against the built image, and Compose validation. On main it preserves the image as the `landing-image` artifact, outside the `candidate-*` pattern the application publication downloads. A `Publish landing` job after the gate pushes `ghcr.io/kl3init/memoryos-landing` and records the digest in the job summary and the `landing-release-<sha>-<attempt>` artifact. The application release bundle keeps its three-image contract.
- Deployment is an operator step documented in `docs/runbooks/landing.md`: Cloudflare DNS (apex and `www` A records to the VPS, DNS-only, redirect rule removed, MX/TXT untouched), the Nginx Proxy Manager proxy host with Let's Encrypt, HSTS and a `www` redirection host, the digest-pinned `docker compose up --wait`, and rollback to the previous digest.

## Verification

- Component tests (`src/App.test.tsx`): one `h1`; every in-page anchor resolves to an element `id`; every `mailto:` targets `aws@vanda.app`; every new-tab link carries `rel="noopener noreferrer"`; every image and SVG has an accessible name or is hidden as decorative. Theme hook tests (`src/lib/theme.test.ts`): a stored theme is applied, and toggling applies and remembers the visitor's choice.
- Metadata test (`tests/site-metadata.test.mjs`): the JSON-LD in `index.html` parses and names Vanda, MemoryOS, GenAI Fund and the contact email; the canonical, `og:image`, `twitter:image` and logo URLs are absolute `https://vanda.app` URLs whose files exist in `public/`.
- `pnpm --dir landing check` (lint, format, tests, production build with font-asset assertion, TypeScript).
- Image: `smoke-image.sh` runs the image read-only with no capabilities and checks UID 101, `/`, `/healthz`, an unknown path (404), the public files, every security header and immutable asset caching. CI runs it on every change.
- Browser: inspect 390, 768 and 1440 px widths and run Lighthouse (mobile) against the container and the deployed URL.
- Deployed: `https://vanda.app` returns 200 with the security headers and HSTS, `www` redirects, unknown paths return 404, and MX records are unchanged.

## Out of scope

Sign-up or sign-in, pricing, blog, Vietnamese localization, analytics or cookies, a contact-form backend, prerendering/SSR, and an automated deployment workflow.

## Risks

- Replacing the apex redirect changes current public behavior of `vanda.app` (owner-approved 2026-09-11).
- The company site shares the staging VPS; its availability follows that server.
- Roadmap capabilities are presented as part of the product by owner decision; content review precedes sending the link to AWS.
