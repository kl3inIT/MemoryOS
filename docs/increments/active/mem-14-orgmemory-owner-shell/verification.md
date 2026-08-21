# MEM-14 verification

## Automated verification

- Final `gradlew.bat clean check --no-daemon`: passed the repository-wide Gradle gate.
- Final `pnpm check`: passed generated-client freshness, Playwright image pin, lint with warnings denied, formatting, TypeScript, 6 unit tests, generated-route freshness, and production build.
- Final `pnpm test:e2e`: passed 7/7 Chromium browser contracts.
- TypeScript compilation validates the new `/admin` route, forwarded-ref sidebar/menu primitives, theme context/provider, app shell, account popover, and route surfaces.
- Product TypeScript and TSX contain no raw white, black, neutral-scale, hexadecimal, or OKLCH color utilities; color values remain confined to the token source.

## Runtime verification

The authenticated route was exercised against the real local Vite runtime with the preview identity API.

Desktop at 1440 × 900 verified:

- 15rem expanded sidebar with compact Hanken Grotesk brand and `New Session`;
- 4rem folded rail;
- folded logo visible at rest and replaced by the expand icon on hover/focus using one button;
- no desktop product topbar or horizontal divider;
- centered `How can I help?` composition and unavailable composer;
- pinned `Admin Panel` and aligned account trigger;
- account popover positioned from the forwarded Radix trigger and containing no actor ID or debug subtitles;
- real light/dark switching and persisted preference;
- separate administration navigation with `Knowledge` and `Sources`.

Mobile at 390 × 844 verified:

- compact product bar;
- modal navigation drawer with backdrop;
- `New Session`, `Admin Panel`, and account trigger alignment;
- explicit close control and Escape behavior;
- readable assistant composition behind the modal.

## Typography verification

- Hanken Grotesk variable assets are included in the production bundle, including the Vietnamese subset.
- Content presets match the Onyx scale: `48/64`, `24/36`, `18/28`, `16/24`, `14/20`, `12/16`, and `10/12`.
- Live `playwright-cli` metrics confirmed a 240px/64px sidebar, 36px rows, `14/20` weight-500 navigation text, 16px icons, a 28px brand mark, DPR 1, and visual viewport scale 1; section labels remain `12/16`.

## Scope confirmation

No backend, OpenAPI, authentication, authorization, session, assistant execution, conversation, connector, ingestion, retrieval, notification, help, or logout contract changed.