# Brand splash, brand loader and direct sign-in

Owner request 2026-09-14: replace the raster boot splash with the supplied MemoryOS wordmark (navy on white) and a Netflix-style intro; remove the intermediate "Sign in to MemoryOS" screen so a signed-out browser goes to the Keycloak login after the splash; use a short form of the intro while pages or their primary data load.

Owner clarification the same day: keep two separate effects. The full intro plays only on a tab's first load and must finish before the Keycloak redirect. Reloads, the return from sign-in and in-app loading use the short form: ribbons draw the wordmark once, then the intro's light sheen crosses it repeatedly until loading ends.

References reviewed on Mobbin (2026-09-14): Netflix iOS launch (wordmark resolving into the mark), Disney+ and HBO Max (light sweep, dark vignette), and WRITER, Maze and ClickUp web loaders (brand mark with looping motion). No library component provides a traced-wordmark animation, so the splash and loader are SVG/CSS; the existing `Skeleton`, `LoaderCircle` and route pending wiring are reused where they still fit.

## Boot splash

`index.html` inlines the wordmark traced to six even-odd paths; `web/src/components/memoryos-wordmark.ts` carries the same data for React, because the splash renders before the bundle loads. `src/splash.css` is linked instead of inlined: the production CSP (`style-src 'self'`, `script-src 'self'`) blocks inline `<style>` elements, style attributes and inline scripts, and the build merges the file into the entry stylesheet.

`public/splash-mode.js`, a blocking same-origin script in the head, sets `data-memoryos-splash` on the root before first paint: `full` when the tab's `sessionStorage` has no `memoryos.introPlayed` marker (then records it), otherwise `short`. Unavailable storage selects `short` rather than replaying the intro on every load.

Both modes open with fourteen clipped strips sliding in from alternating sides (about 0.9 s) followed by a sheen. `lib/boot-splash.ts` starts the exit once the application has rendered and the mode's intro time has passed (full 1.25 s, short 1.5 s), removes the splash on its exit animation or a 1.6 s fallback, and signals completion.

- Full: the sheen plays once; the exit pulls back, zooms through the first O, bursts brand-blue ribbons and opens a radial mask driven by a registered percentage property onto the application.
- Short: the sheen repeats every 1.4 s while waiting; the exit fades out in 300 ms.

Reduced motion shows the static wordmark and fades in 200 ms. The splash blocks pointer input only until the exit starts.

## Direct sign-in

`ApplicationSessionBoundary` renders `SignInRedirect` for a `401` identity result. It shows the brand loader, waits for the boot splash to finish, then assigns `/oauth2/authorization/memoryos`. Callback, failure (`/access-not-provisioned`, invitation) and logout (`post_logout_redirect_uri` of `/`) contracts are unchanged, so a completed logout lands on the provider login.

Hardening: the redirect time is kept in `sessionStorage`. If the tab is still signed out within 30 seconds of its own redirect, the manual sign-in gate is shown instead of redirecting again; a confirmed identity clears the marker. Unavailable storage degrades to redirecting without the guard.

## Brand loader

`BrandLoader` is the short form of the splash and is visual only; callers keep role, live-region and busy semantics. Ribbons draw the wordmark once, a solid wordmark replaces the strips, and the sheen repeats every 1.4 s until the loader unmounts. The wordmark uses `currentColor` (brand navy in light, `content-primary` in dark) and the sheen is white in light and brand blue in dark. Reduced motion renders the static wordmark without a sheen.

Applied to full-page waits through `RoutePending` (route pending, session check, sign-in redirect) and to page-level primary loads that showed a spinner: Group detail, Sources list, Source detail and Search results. Skeleton placeholders for tables and lists, and spinners inside buttons, badges, dialogs, attachments and the Chat connection state, are unchanged because they preserve layout or sit inside controls.

Out of scope: backend security changes, returning to a deep link after login, a dark splash variant, commit, PR and Linear updates.
