# MEM-82 verification

## Landing 6.0

Recorded 2026-09-12 on branch `nhuxuanviet/mem-82-landing-page-6.0`, commits `48ed13e`–`1bf0000` on top of `bae43dc` (PR #95, the rollback point). The browser review drove the browser embedded in Orca through the `orca` CLI; Lighthouse ran its own headless Chrome.

Package gate, `pnpm --dir landing check` at `1bf0000`: oxlint and oxfmt clean; Vitest 5 files, 50 tests passed; build, WOFF2 assertion and `tsc -b` passed.

Lighthouse 12.8.2 CLI, mobile, performance only, against `vite preview`:

| Build | Score | FCP | LCP | TBT | CLS |
| --- | --- | --- | --- | --- | --- |
| First 6.0 build, motion set up during the React commit | 0.78 | — | — | 750 ms | 0 |
| `1bf0000`, setup after the first frame | 0.96 | 1.8 s | 2.1 s | 160 ms | 0 |

The trace of the first build showed one long startup task: setting up motion during the commit forced a cold layout of the whole page. The fix is described in [design](design.md#visual-direction).

Orca, desktop 1881 × 1019, dark theme:

- The hero types all 74 characters of the statement, then brings in the actions and preview.
- How it works now runs as a vertical beam, with the pinned row removed. At desktop width each station is 403 px tall and its drawing 576 px wide; on a 393 px phone the drawing is 325 px wide, with no overflow. Stations build in order as they cross the viewport and turn live once built. Scrolling back up resets the stations below the viewport to `--p` 0 and clears `data-live`.
- The Product highlights are line drawings now, driven by the same scrub as the beam. At desktop width each drawing is 608 × 380 px; each reached `--p` 1 and `data-live` after scrolling past it, and the rows carry `data-visible` while on screen. On a 393 px phone each drawing is 361 × 226 px, with no horizontal overflow.
- Finished stations keep their loops while the list is on screen, measured as running CSS animations: 6 beam runs, 4 packets, 1 scan, 2 blinks, 24 levels, 1 pulse and 2 glints. Screenshots taken 1.3 s apart show light moving along the beam. Once the list leaves the screen, `data-visible` is removed.
- Scrolling the whole page down and back up raised no `error` or `unhandledrejection` events and no Vite error overlay. There was no horizontal overflow.

Other viewports and themes:

| Check | Result |
| --- | --- |
| iPad (820 × 1180) | Stacked beam, one scrubbed timeline per station; no overflow, no errors |
| iPad Pro (1024 × 1366) | Horizontal beam; no overflow, no errors |
| iPhone 14 (390 × 844), before the loops | Stacked; no overflow |
| Light theme | Hero, trust strip and the beam render with the light tokens |

Screenshots taken under Orca's device emulation tile the viewport, so the tablet evidence is measured rather than seen.

Orca's window was not focused during the review. The browser therefore delivered frames slowly, and GSAP's lag smoothing stretched the 0.6 s scrub to several seconds. Right after scrolling, the last station read `--p` 0.999987 and was not yet live; it completed a few seconds later, which is expected behaviour.

Reduced motion could not be emulated: `orca set media --reduced-motion reduce` leaves the media query unmatched. The static state is covered by the jsdom test that renders no inline motion styles and no motion state attributes.

Still to run for 6.0:

- A phone-width pass over the sections set as type (see [type across the page](#type-across-the-page)).
- A keyboard pass over the new sections.
- The Docker image and its smoke script.
- CI on a pull request.

### Merge of the positioning revision

Recorded 2026-09-12 after merging `origin/main` into the 6.0 branch, with the product owner's three decisions in [design](design.md#accepted-decisions-2026-09-11).

- `pnpm --dir landing check`: oxlint and oxfmt clean; Vitest 4 files, 19 tests passed; build, WOFF2 assertion and `tsc -b` passed. The first run failed: the heading lookup `/Index/` also matched "Index any file", and "Enterprise identity" also begins a Product heading. The lookup is now anchored at the end of the heading.
- `public/og-image.png` was re-rendered from `scripts/og-image.html` with headless Chrome. It shows "MemoryOS by Vadan", "Trusted by Tasco. Backed by GenAI Fund." and `vadan.app`.
- Orca, desktop 1881 × 1019, dark theme: the page renders six sections with 27 level-3 headings and no horizontal overflow. The access drawing reached `--p` 1: search, agents and MCP clients meet at the lock, and two documents are reached while two are stopped. The files mock, the Organizational AI Memory section and the three deployment options render main's copy.
- The first load after the merge was blank. The Vite dev server still resolved `@/sections/product-highlights` to the deleted `product-highlights.tsx`. Touching `App.tsx` made it resolve the folder again. The production build was not affected.
- How it works as type on a horizontal track (plan Step 14), 2026-09-12: `pnpm --dir landing check` passed (Vitest 4 files, 19 tests; build and `tsc -b`). The first run failed on `tsc -b`: an imported `ScrollTrigger` was unused, because `ScrollTrigger.Vars` resolves to GSAP's global namespace. In Orca at 1881 × 1019 the stage pins from 6643 to 10803, which is the row's overflow. The counter reads 01, 02, 04 and 06, and the progress hairline scales 1/6, 1/3, 2/3 and 1. Each stage's letters rise and its words go from 0.15 to full opacity as it slides in, and they reverse on the way back up. The last stage completes where the row stops, at 773 px from the left. Titles are 125 px and sentences 52 px, with no horizontal overflow. Under Orca's iPhone 12 emulation (390 × 844) the list stays stacked with no `data-track` and no overflow; titles are 48 px and sentences 24 px, and the stages reveal in order and reverse when scrolling up. With a containerAnimation, ScrollTrigger reports `start` and `end` in seconds of the row's timeline, not in pixels, so those triggers read 0 when rounded.
- After a reload, the first deep scroll runs ScrollTrigger's refresh. A trace showed it scroll to 0 to measure and then restore the position (5023 → 0 → 5023). Once, with Orca's window unfocused, the position stayed at 0; two traced attempts did not reproduce it.

### Type across the page

Recorded 2026-09-12 for plan Step 15.

`pnpm --dir landing check`: oxlint and oxfmt clean; Vitest 4 files, 19 tests passed; build, WOFF2 assertion and `tsc -b` passed. The first lint run failed on an unused constant left from the rejected scattered hero intro.

Lighthouse 12.8.2 CLI, mobile, performance only, against `vite preview`, two runs each:

| Build | Score | TBT |
| --- | --- | --- |
| Type across the page, before the fix | 0.74, 0.83 | 1040 ms, 520 ms |
| `splitText` reads the pieces' transforms in one pass | 0.94, 0.96 | 190 ms, 130 ms |

The trace of the slow build showed 608 forced layouts inside GSAP's transform parsing (`_parseTransform`) as tweens started on fresh SplitText pieces; the split page has 1544 DOM elements. With the fix, forced layout fell from 261 ms to 118 ms. CLS is 0.002, from the web font swap, against the design's target of 0.

Orca, desktop 1881 × 1019:

- Computed sizes: the hero headline and the stage titles 72 px; section headings 56 px; entry titles (Product, capabilities, deployment) and stage sentences 36 px; descriptions 22 px; FAQ questions 22 px and answers 16 px.
- The trust strip's bottom edge is at 1019.9 px, the bottom of the first screen.
- How it works: 64 px between the section heading and the track, and the pinned stage spans 306–714 px of the 1019 px viewport.
- The headline's letters settle by about 1.9 s. The split is then reverted, leaving no masks and no `aria-label` on the `h1`.
- The Vadan logo renders at 54 × 14 px in the muted text colour in the header and the footer.
- Light theme: the glow behind the hero and the footer reads as blue.

Not yet run after Step 15: the phone-width pass (Orca's runtime closed while creating a tab and setting a device, so the check was stopped rather than risk crashing it again), the keyboard pass, the Docker smoke and CI.

## Landing 5.x

Recorded 2026-09-11 on branch `nhuxuanviet/mem-82-landing-page` at `b657c44`, on Windows 11 with Docker Desktop and Chrome through Chrome DevTools MCP. Everything below ran locally; the pending gates at the end have not run.

### Package gate

`pnpm --dir landing check`:

- oxlint with `--deny-warnings`: no findings.
- oxfmt: all matched files formatted.
- Vitest: 4 files, 14 tests passed (page structure and content, site metadata and public files, theme persistence).
- Vite production build, the WOFF2 assertion (4 emitted font assets, no inline font URLs) and `tsc -b`: passed.

### Image and Compose

| Check | Result |
| --- | --- |
| `docker build --tag memoryos-landing:local landing` | Built from `b657c44` |
| `bash landing/scripts/smoke-image.sh memoryos-landing:local` | `landing smoke: memoryos-landing:local passed` |
| Negative: the smoke script against the unmodified `nginx:1.31-alpine` base (same digest) | Exit 1, `landing smoke: no answer on /healthz`; the base cannot start read-only and has no `/healthz` |
| `docker compose --file infrastructure/deployment/compose.landing.yaml config --quiet` with `MEMORYOS_LANDING_IMAGE` set | Exit 0 |
| `up --detach --wait` on a local `proxy-network` | `memoryos-landing` Healthy; `/healthz` returned `ok` |
| `docker inspect memoryos-landing` | User `101:101`, read-only root, `CapDrop=[ALL]`, `no-new-privileges:true`, 64 MiB memory, 0.25 CPU, init |

The test network and container were removed afterwards.

### Workflow and scripts

- `rhysd/actionlint:1.7.12` over `.github/workflows`: no findings after one narrow SC2016 suppression for Markdown backticks in the `Publish landing` step summary.
- `koalaman/shellcheck:stable` on `infrastructure/deployment/deploy-staging.sh` and `landing/scripts/smoke-image.sh`: no findings.

### Browser review

The production image ran as `docker run --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true` on `127.0.0.1:18092`.

- Console: no messages, so no Content Security Policy violations.
- Headers on `/`: the CSP, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy` and `Permissions-Policy`. `/missing` returns 404. HSTS is absent locally by design; Nginx Proxy Manager adds it on the deployed host.
- Lighthouse, mobile, Chrome DevTools MCP: Accessibility 100, Best Practices 100, SEO 100.
- Lighthouse 12.8.2 CLI, mobile, performance only (the MCP audit does not score performance): 99, with FCP 1.7 s, LCP 1.9 s, TBT 10 ms, CLS 0.
- Performance trace with 4× CPU and Slow 4G: LCP 1.95 s, CLS 0.

Viewports, dark theme:

| Width | Result |
| --- | --- |
| 390 px | Compact header and menu; no horizontal overflow |
| 768 px | Initially the desktop navigation overflowed the header (page 789 px wide, contact button clipped). Fixed in `05d3b8d`: the desktop navigation starts at 1024 px. Re-checked: 768 px wide, compact menu |
| 1024 px | Desktop navigation fits on one row; no overflow |
| 1440 px | Hero, preview and trust strip laid out as designed; logos vertically centred |

Keyboard, at 1024 px:

- The first Tab shows "Skip to content" with a 2 px focus outline. It first collapsed onto the brand because `not-sr-only` reset its padding. Fixed in `b657c44`; it now has 8 × 12 px padding above the header.
- Enter on the skip link moves to `#main`, and the next Tab reaches the hero's "Contact us".
- The tab order runs through the header, the hero actions, the seven FAQ questions and the three footer links. Enter opens a FAQ answer and Space closes it, with a visible focus outline.

The earlier theme review covered no light flash before first paint, a stored choice overriding the system theme, the circular theme reveal and the scroll-linked reveals. Reduced motion could not be emulated with this tool. It relies on the `prefers-reduced-motion` CSS guards and the script's `matchMedia` check.

## Positioning revision

Recorded 2026-09-11 on branch `nhuxuanviet/mem-82-landing-positioning` (from `c8e60e5`), after the product owner's positioning, domain (`vadan.app`) and contact (`info@vadan.app`) decisions in [design](design.md).

- `pnpm check`: oxlint and oxfmt clean; Vitest 4 files, 15 tests passed (adds "does not link to the private source repository"; the email test now expects `mailto:info@vadan.app`); production build, WOFF2 assertion and `tsc -b` passed.
- `gradlew.bat clean check --no-daemon`: BUILD SUCCESSFUL (no Gradle sources changed).
- `docker build` plus `landing/scripts/smoke-image.sh memoryos-landing:local`: passed.
- `public/og-image.png` re-rendered from `scripts/og-image.html` with headless Chrome.
- Browser, dark theme, reduced motion: 1440 px reviewed section by section with no layout defects. At 390 px the capabilities, AI Memory text and asset card, how-it-works and deployment cards stack without overflow in the last successful capture; the final AI Memory surfaces band was not re-captured at 390 px.
- The commits `2949b78` and `bae43dc` pushed to `nhuxuanviet/mem-82-landing-page` after PR #95 merged were not taken: the product owner chose "design partner" for Tasco instead of "partner".

## Pending gates

These have not run and are not passed:

- CI on the pull request, including the new `landing` job in `CI Gate`.
- The first `Publish landing` run on main and its recorded digest.
- The operator deployment on the staging VPS, the Hostinger DNS and Nginx Proxy Manager changes, and the deployed checks from the [landing runbook](../../../runbooks/landing.md).
- Lighthouse against `https://vadan.app/`.
- Laura's content review.
