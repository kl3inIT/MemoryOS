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

No realm, client or deployment change is needed. Keycloak ending its own session does not end an upstream identity provider's session (for example Tasco), as before. Incoming back-channel logout remains out of scope.
