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
- Landing page 6.0 (product owner, same day, on branch `nhuxuanviet/mem-82-landing-page-6.0` so pull request #95 stays the rollback point): less text — each block is a title plus one sentence of at most 12 words, and technical detail moves to the FAQ; no decorative icons; capabilities, how it works, the AWS diagram and the roadmap are told through motion. antigravity.google is the reference for effects, pacing and feature highlighting only, not for layout or visual identity, and the page has no video.

## Reference baseline

Open SaaS (`wasp-lang/open-saas`, MIT) at commit `cbd30162b05d798b3a3f955ab5781940b67bec89`, `template/app/src/landing-page/` and `template/app/src/client/components/NavBar/`.

Observed: `LandingPage.tsx` composes Hero → ExamplesCarousel → highlighted feature → FeaturesGrid (bento) → Testimonials → FAQ → Footer. All copy lives in `contentSections.tsx`. `SchemaMarkup.tsx` emits JSON-LD. The NavBar is sticky and shrinks after scrolling, with a Radix Sheet on mobile.

Kept: the section composition, one typed content module, the text-plus-visual highlighted feature, FAQ, footer, JSON-LD, and a sticky header.

| Open SaaS | MemoryOS landing | Reason |
| --- | --- | --- |
| Wasp app with routes, auth and payments | Static single-page Vite build, no auth | Only public content is needed; product sign-in stays in `web/` |
| Testimonials, examples carousel, client logos | Trust strip with the Tasco and GenAI Fund logos | No fabricated social proof; the product owner supplied both logos for this page |
| Radix Accordion and Sheet | Native `<details>` for FAQ and mobile menu | Same behavior without a component-library dependency on a static page |
| NavBar that shrinks on scroll | Fixed-height sticky header | No scroll listener for a cosmetic change |
| Default palette, gradients, dark-mode switcher | Navy and brand-blue light and dark tokens with one hero glow, Hanken Grotesk, and a header theme toggle | Product-owner direction: a technical look in the manner of Laravel Cloud, on the product's own token names and typography |
| Screenshot images in the highlighted feature | Markup-built product preview (question → cited answer) | Crisp at every width and needs no screenshot pipeline or customer data |
| FeaturesGrid (bento) of icon cards | Capability cards, each with a markup mock of the product, converging from both sides as they scroll in | Landing page 6.0: fewer words, and each capability shown working instead of named beside an icon |
| JSON-LD rendered by React | Static JSON-LD in `index.html` | Visible to crawlers without JavaScript; data blocks do not execute under the strict CSP |

## Page

1. **Header** — MemoryOS mark, anchors (Product, How it works, Deployment, Roadmap, FAQ), light/dark theme toggle (inside the menu below `md`), Contact action (`mailto:aws@vanda.app`), skip link.
2. **Hero** — the still `h1`, a typed one-sentence positioning statement, Contact and "See how it works" actions, the product preview, and a particle field behind them.
3. **Trust strip** — the Tasco and GenAI Fund logos with "Partnering with Tasco" and "Backed by GenAI Fund". The logos are single-colour alpha masks (`src/assets/logos/`) cropped from the product owner's files, so they take each theme's content color.
4. **Problem → solution** — two highlighted features: scattered company knowledge becomes one place to search and ask with citations; enterprise AI stays governed through one permission model shared by search, agents and MCP.
5. **How it works** — six ingestion stages (Pull, Extract, Chunk, Embed, Index, Answer) as stations down one vertical beam of light at every width, each beside a large line drawing of a sample supplier policy in that stage's form: the sources converge into the page; its regions are outlined in reading order, scanned, and read into text and a table; the text splits into passages that keep source and access tags; each passage becomes a vector and a point; the points join the index; a question finds the closest two passages and the answer cites them. The drawings replace boxed scenes with labels. Then the access gate on every request: a signed-in person, the access check that strikes out passages they may not read, and the model that receives only the rest.
6. **Capabilities** — nine capabilities, each a title, one sentence and a small animated mock of the product doing it: data connectors; enterprise search; cited answers; Python analysis and reports; enterprise SSO; custom agents; AI asset governance; MCP server; permission-aware retrieval. How it works comes first so the cards build on the ingestion story.
7. **Deployment on AWS** — the PoC architecture as an animated markup diagram that keeps every service name: people reach the application EC2 over HTTPS; the application and data EC2 hosts sit in a private VPC inside the customer's AWS account; only permitted context reaches the managed model endpoint; S3 and EBS snapshot backups, CloudWatch, KMS and Secrets Manager, IAM and ECR are shared services. No prices, credit amounts or instance sizes.
8. **Roadmap** — the four monthly PoC milestones from September to December 2026, each with its one deliverable, then a condensed "After the PoC" list: company-wide rollout, more business systems, web search and deep research, high availability.
9. **FAQ** and **Footer** — the FAQ carries the technical detail the sections no longer spell out; the footer holds the contact call to action over a second particle field and © Vanda. It links neither the source repository nor the third-party notices; `/THIRD_PARTY_NOTICES.txt` stays served.

## Visual direction

The product owner asked for a technical look in the manner of Laravel Cloud. Hanken Grotesk and the product's token names stay; the values become a navy-and-blue identity: a near-black navy ground in the dark theme, a cool light ground in the light theme, and one light source — a blue glow behind the hero's cited-answer preview, echoed softly behind the closing call to action. The preview stays the one emphasized element, because a verifiable answer is what MemoryOS delivers. Brand blue marks the product's own moving parts: citations, the passages and query in the ingestion story, the access check, the links of the deployment diagram, and the markers of real sequences (ingestion stages, roadmap timeline). Approval green is used only for an approved AI asset. Headings stay left-aligned without labels above them. Sections carry no decorative icons: each capability is shown by a small markup mock of the product doing it, framed by a thin gradient border.

Motion carries the explanation, and answers the visitor's own actions. Switching the theme grows the new theme in a circle from the toggle (View Transitions API). On load the `h1` stays still, so it paints as the largest contentful element immediately, while the positioning statement types itself beside a caret and the actions and preview follow. Scrolling drives the rest through GSAP ScrollTrigger, in both directions:

- **How it works** lights the beam station by station as each station crosses the viewport. The list is never pinned, so the drawings can stay large; an earlier horizontal row pinned them but kept them small. Each station has one progress value, `--p`, and the `ramp` utilities in `src/styles/base.css` turn it into each part's fade, movement, growth or stroke drawing, so the whole story runs six tweens. A finished station keeps a quiet CSS loop while the list is on screen: light runs along the lit beam, packets reach the page, the scan sweeps again, the cuts flash, the vector bars shift, the index pulses, and the question keeps reaching the passages it cites. The access gate then assembles: its links grow, passages fly from the person to the check, and the blocked ones are struck through.
- **Capabilities** brings its cards in from both sides. From lg the left column slides in from the left and the right column from the right. The last card, the principle the others share, spans both columns and rises from the middle. Each mock plays once its card has arrived. An earlier pinned explorer showed only one small mock at a time. Mocks declare their entrances as data attributes, and one interpreter builds a mock's timeline when it is first needed.
- **Deployment** assembles its boundaries, hosts and links once, then sends packets along the links while the diagram is on screen.
- **Roadmap** fills its timeline and brings in each milestone and deliverable; the "After the PoC" line extends from the last milestone.
- The hero glow drifts away, and canvas particle fields drift behind the hero and the closing call to action; they pause off screen and in background tabs.

No scene is pinned; every scene scrubs or plays in place. The markup always holds the final state: GSAP sets start states only while `prefers-reduced-motion: no-preference` matches, so reduced motion, a failed script and the component tests all render the complete static page. Only transient marks — scan lines, flying copies, packets, the beam's light and the caret — are hidden in the markup.

Motion sets up after the first frame, each section in a task of its own. Setting up reads styles and layout; done while the page mounts, it made the browser lay out the whole page inside that task, and Lighthouse mobile performance fell to 0.78 (TBT 750 ms). The hero is the one part whose start state must be in the first frame, so before that frame it only sets `data-intro="pending"`, which CSS reads to hide the statement, actions and preview until its sequence takes over.

## Technical design

- `landing/` is a standalone pnpm package (own lockfile, not a workspace member of `web/`). It uses the same pinned versions as `web/` for React, Vite, TypeScript, Tailwind, lucide-react, clsx/tailwind-merge, Hanken Grotesk, Vitest, Testing Library, oxlint and oxfmt. There is no router, query client, API client, Radix or class-variance-authority. lucide-react remains for interface controls only (menu, theme toggle, disclosure, lock and checks).
- GSAP 3.15.0 and `@gsap/react` 2.1.2 (exact pins, bundled, so the strict CSP holds: GSAP writes styles through the CSSOM) drive the motion. `src/motion/motion.ts` registers ScrollTrigger and exposes `useMotion`, which runs each section's setup after the first frame, in page order, inside one `gsap.matchMedia` context keyed on reduced motion, width and height; unmounting or a media change reverts it to the static markup. `allowsMotion` answers the same reduced-motion query for the hero's pre-frame attribute. Its `offsetTo` measures layout through offset chains, so flights ignore transforms that are still animating. `src/components/particle-field.tsx` draws the canvas fields, and `src/sections/capabilities/animate-mock.ts` turns the `data-enter` attributes of a mock into a paused timeline. `src/styles/base.css` holds the `ramp` utilities, the ingestion loops and the hero's pre-frame rule.
- `src/content.ts` owns every string, link and list; section components under `src/sections/` render it. The exception is the sample data inside the decorative, `aria-hidden` illustrations — the ingestion story's document, vector plot and index, and the capability mocks — which lives with its illustration and only restates what the visible copy says. Shared primitives live under `src/components/`: `ActionLink` (default-tone rows of the action matrix as links), `Section` (landmark, heading and description), `BrandMark` and `ParticleField`; `src/lib/illustration.ts` holds the class strings the illustrations share.
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

- Component tests (`src/App.test.tsx`): one `h1`; every in-page anchor resolves to an element `id`; every `mailto:` targets `aws@vanda.app`; every new-tab link carries `rel="noopener noreferrer"`; no link to the source repository or the third-party notices; every image, SVG and canvas has an accessible name or is hidden as decorative; the typed statement reads as one sentence; how it works precedes capabilities; every ingestion stage and capability has a heading and a visible sentence; the access gate lists the blocked and the permitted passages; without motion no element carries an inline opacity, transform or visibility, the explorer is not pinned, and no motion state (`data-intro`, `data-live`, `data-visible`) is set. Copy tests (`src/content.test.ts`): every short line is one sentence of at most 12 words. Theme hook tests (`src/lib/theme.test.ts`): a stored theme is applied, and toggling applies and remembers the visitor's choice.
- Metadata test (`tests/site-metadata.test.mjs`): the JSON-LD in `index.html` parses and names Vanda, MemoryOS, GenAI Fund and the contact email; the canonical, `og:image`, `twitter:image` and logo URLs are absolute `https://vanda.app` URLs whose files exist in `public/`.
- `pnpm --dir landing check` (lint, format, tests, production build with font-asset assertion, TypeScript).
- Image: `smoke-image.sh` runs the image read-only with no capabilities and checks UID 101, `/`, `/healthz`, an unknown path (404), the public files, every security header and immutable asset caching. CI runs it on every change.
- Browser: inspect 390, 768, 1024 and 1440 px widths in both themes, scroll every scrubbed scene in both directions, watch the ingestion loops start and stop, check the static page with reduced motion, and run Lighthouse (mobile) against the container and the deployed URL; performance stays at or above 90 with CLS 0.
- Deployed: `https://vanda.app` returns 200 with the security headers and HSTS, `www` redirects, unknown paths return 404, and MX records are unchanged.

## Out of scope

Sign-up or sign-in, pricing, blog, Vietnamese localization, analytics or cookies, a contact-form backend, prerendering/SSR, and an automated deployment workflow.

## Risks

- Replacing the apex redirect changes current public behavior of `vanda.app` (owner-approved 2026-09-11).
- The company site shares the staging VPS; its availability follows that server.
- Roadmap capabilities are presented as part of the product by owner decision; content review precedes sending the link to AWS.
