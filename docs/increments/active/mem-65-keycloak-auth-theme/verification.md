# MEM-65 verification

## Theme and runtime contracts

- The theme contract suite passed all four checks: `keycloak.v2` inheritance, no copied FreeMarker templates, local-only declared assets, coverage for login and required-action states, the read-only Compose mount, and fail-closed realm reconciliation.
- `bash -n infrastructure/keycloak/configure-memoryos-realm.sh` passed.
- `docker compose -f infrastructure/deployment/compose.base.yaml config --quiet` passed with validation-only values. The rendered Keycloak service binds `infrastructure/keycloak/themes/memoryos` to `/opt/keycloak/themes/memoryos` with `read_only: true`.
- The focused Chromium suite passed all three tests at 1440x900 and 390x844, including no document overflow, the centered identity/card composition with its bounded inward identity offset, the single-card mobile composition, visible keyboard focus, removed principle pills/hostname badge, and the login, update-password, verify-email, info, and error visual states. A 1909x951 review render placed the identity at x=479 and retained the 468x610 card at x=1018, moving the logo and name 56px closer to the center without moving the form.

## Live Keycloak 26.7.0 browser evidence

- The repository theme was loaded by the official Keycloak 26.7.0 distribution with the production cache disabled only in this disposable validation runtime. The isolated `memoryos` realm selected `loginTheme=memoryos`; no FreeMarker template was added to make the flow operable.
- The real realm reconciliation completed and replayed successfully against that runtime. It first confirmed the advertised `memoryos` login theme, then persisted and read back `loginTheme=memoryos` before reconciling the remaining clients and roles. Live validation also caught that Keycloak's `kcadm --fields themes` returns an empty object for `serverinfo`; the availability check now reads the complete representation before selecting `.themes.login`.
- Real inherited provider pages rendered for sign-in, reset credentials, first-login password update, verify profile, email verification, and an invalid action-token error. The login, password update, and email verification pages produced no browser console errors or warnings. The invalid-token request intentionally returned HTTP 400 while still rendering the themed `login-error` page.
- Desktop authentication rendered a 468x610 foreground card over the meaning network. At 390x844, the document remained exactly 390x844 and the card stayed inside the viewport. The 1280x720 password-update and verification pages also had equal document and viewport dimensions.
- The real Keycloak v2 input DOM places each input inside a `.pf-v5-c-form-control` wrapper. The CSS and browser fixture were corrected against that provider-owned structure so full-width fields, focus rings, error borders, password toggles, and the forgot-password placement are verified at the actual inheritance boundary.
- Live flow inspection exposed that `doSubmit` is shared across multiple inherited pages. The final message bundle uses the truthful generic `Continue` label rather than mislabeling profile submission as password creation. The terminal title is likewise generic because `login-error` also serves non-expiry failures.

## Repository gates

- `pnpm --dir web check` passed under Node 24. The local default Node 26.7.0 run exposed its experimental web-storage behavior in existing unit tests; rerunning on the CI-pinned Node 24 completed 11 test files / 44 tests, lint, formatting, type checking, generated-client stability, production build, route generation, and local font checks.
- `gradlew.bat clean check --no-daemon` compiled all modules and completed the non-container work, but the repository-wide gate could not finish because Docker Desktop was unavailable. Six existing Testcontainers-backed object-storage tests failed with `Previous attempts to find a Docker environment failed`; this theme change does not touch those tests or Java code.
- `git diff --check` is part of the final tree review.

The Compose-backed shared-runtime rollout remains a deployment step. The same Keycloak version and exact provider templates were exercised locally from the official distribution despite the unavailable container daemon.
