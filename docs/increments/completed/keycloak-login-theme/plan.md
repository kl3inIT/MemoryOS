# Plan

- [x] Add the `memoryos` login theme over `keycloak.v2`: stylesheet, traced wordmark SVG and English title/provider-divider messages, with dark mode off.
- [x] Mount the theme read-only into `shared-keycloak` and select it in `configure-memoryos-realm.sh`; document the recreate-then-replay operator order.
- [x] Verify on a local Keycloak 26.7.0 with a realm mirroring MemoryOS sign-in settings.
- [x] Owner follow-ups: small Tasco logo to the right of the configured display name; no Google test provider; solid colours with no gradients, soft shadows or glows; stronger hover colours; one full-width button per provider.
- [ ] Owner review of the page and of the Tasco button's accessible name; commit, PR, shared-runtime rollout and Linear remain owner decisions.

## Verification — 2026-09-15

Local `quay.io/keycloak/keycloak:26.7.0` in `start-dev`, theme mounted read-only at `/opt/keycloak/themes/memoryos`, realm `memoryos` with email as username, login with email, self-registration disabled and `loginTheme=memoryos`; OIDC identity provider `tasco` with display name "Đăng nhập với"; Playwright on Chromium.

- The sign-in page loads the theme's stylesheet, wordmark and Tasco logo with no failed responses: flat background, bordered white card, wordmark, "Continue to MemoryOS", Email and Password, navy "Sign In", "Or continue with" and the provider button. The stylesheet contains no gradient and no box shadow other than `box-shadow: none` on PatternFly's panel.
- Sign In is `rgb(8, 51, 114)` with no background image and `rgb(5, 36, 82)` on hover.
- The Tasco button is full width (350 px) and reads "Đăng nhập với" followed by the TASCO logo, masked from `img/tasco-logo.png` at 76.9 × 11.2 px for the 16 px button font. Pixel measurement at device scale 3 puts the logo's ink at 14.67–25.67 px against 14.33–25.67 px for the capital "Đ", at rest and on hover: bottoms match and tops differ by a third of a pixel. Its accessible name is "Đăng nhập với". At rest the button is white with dark name and logo; on hover it is navy with both white.
- With display name "Đăng nhập với Tasco" the same button showed that name with the logo beside it. An added OIDC provider `partner-sso` without a display name showed its alias as the button text, full width, with the default icon and no logo; it was removed afterwards. Earlier, `github` and `microsoft` providers rendered their default icon and name.
- Focusing a field turns its border blue without the square browser outline. Wrong credentials show Keycloak's inline error in the card. At 390 px the card fills the width with a gutter. With the operating system preferring dark, the page stays light.
- Correct credentials for a complete user return the browser to the client's redirect URI. A user without first and last name gets Keycloak's "Update Account Information" page in the same card, full width, with the wordmark (checked after network idle; an earlier capture taken before the SVG loaded showed an empty header).
- `docker compose -f infrastructure/deployment/compose.base.yaml config --no-interpolate` accepts the read-only bind mount, and `bash -n` accepts `configure-memoryos-realm.sh`.

Not verified: the shared staging Keycloak, which was not changed.
