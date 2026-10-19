# Sign-out without the Keycloak logout page

Owner request 2026-09-15: signing out from the account menu currently lands on Keycloak's "Logging out — Do you want to log out?" page. End the Keycloak session through an API call so the browser goes back to MemoryOS without that page.

## Current behaviour

`POST /logout` invalidates the local session and returns `X-MemoryOS-Logout-Location`, the RP-initiated logout URL (`client_id` and `post_logout_redirect_uri`). Without `id_token_hint` Keycloak asks for confirmation, and the application cannot send one because the login callback discards provider tokens.

## Options considered

- Keep RP-initiated logout with `id_token_hint`: requires storing the ID token in the browser session, contrary to the accepted "no provider tokens in the session" contract.
- `POST /admin/realms/{realm}/users/{id}/logout`: ends every session of the user on every device.
- Chosen: `DELETE /admin/realms/{realm}/sessions/{sid}` ends only the Keycloak session that opened this browser session. Verified on local Keycloak 26.7.0: the ID token carries `sid`, the id is one of the user's sessions, deletion returns `204`, a repeat returns `404`, and the refresh token of the deleted session is rejected with `invalid_grant`.

## Design

- The login success handler stores the ID token `sid` as a string HTTP-session attribute (`ProviderSessionState`) after admission; rejected logins store nothing and provider tokens are still discarded.
- `ProviderSessionTerminator` (IAM identity named interface) ends a provider session; `KeycloakProviderSessionTerminator` implements it with the existing admin client and the provisioner service account, whose realm-local `manage-users` role already authorizes session deletion. A `404` counts as ended; a refused or unreachable admin API does not.
- `ProviderSessionLogoutHandler` runs before Spring invalidates the session, ends the recorded session and remembers the outcome. A provider exception never blocks the local sign-out.
- `SessionLogoutSuccessHandler` returns `204` without a location when the provider session ended, and keeps returning the provider logout location otherwise (no `sid`, sessions created before this change, Keycloak unavailable).
- The account menu navigates to the returned location when present and to `/` otherwise; the signed-out app then goes straight to Keycloak sign-in.

No realm, client or deployment change is needed. Incoming back-channel logout remains out of scope.

## Brokered sessions (Tasco)

Owner report 2026-09-15: after signing in through Tasco, sign-out did not really sign out. "Đăng nhập bằng TASCO" signed straight back in, so the user could neither enter credentials again nor switch account.

Cause, from the Keycloak 26.7.0 source and reproduced locally: the admin session delete runs Keycloak's back-channel logout, and `OIDCIdentityProvider.backchannelLogout` calls the upstream provider only when its config has `logoutUrl` and `backchannelSupported=true`. MemoryOS created providers with a discovered logout URL but without back-channel support. The RP-initiated browser logout used before this change redirected through the upstream logout endpoint instead, so removing that page also removed upstream logout.

Options considered:

- Redirect the browser through the upstream logout after the admin delete: needs the upstream ID token as `id_token_hint`, which only Keycloak stores, and without it the upstream shows its own confirmation page.
- `prompt=login` on the provider: forces credentials on every sign-in and gives up upstream single sign-on.
- Chosen: identity-provider create and update set `backchannelSupported=true` whenever the provider has a logout URL and remove it otherwise. Keycloak then sends a server-side request to the upstream end-session endpoint with the stored upstream ID token. A Keycloak upstream (Tasco is one) accepts it without a browser cookie or confirmation: the hint names its client and session. The provider-page fallback also uses back-channel logout once it is enabled.

A provider that already exists needs the setting once: save it again in MemoryOS identity-provider administration after deployment, or switch on **Backchannel logout** for it in the Keycloak admin console. An upstream that does not accept a server-side end-session request keeps its session.
