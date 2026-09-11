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

- A visual pass over the capabilities, deployment and roadmap scenes while scrolling up.
- A keyboard pass over the new sections.
- The Docker image and its smoke script.
- CI on a pull request.

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

### Pending gates

These have not run and are not passed:

- CI on the pull request, including the new `landing` job in `CI Gate`.
- The first `Publish landing` run on main and its recorded digest.
- The operator deployment on the staging VPS, the Cloudflare and Nginx Proxy Manager changes, and the deployed checks from the [landing runbook](../../../runbooks/landing.md).
- Lighthouse against `https://vadan.app/`.
- Laura's content review.
