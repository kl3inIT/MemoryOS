# Implementation plan

Design: [design.md](design.md). Two pull requests, one per vendor.

## Pull request 1: SharePoint on the Microsoft Graph SDK

- [x] Probe on a local server: redirects and retries of the SDK switched off, a response stopped at its bound, the
  token kept on `msal4j` through the SDK's `AccessTokenProvider`.
- [x] `microsoft-graph` 6.70.0 in `sources`.
- [x] `GraphTransport`: the OkHttp client of the SDK with the guard (bound, failed body unread, deadline) and the
  SDK's redirect handler at zero redirects.
- [x] `GraphModels`: the SDK's models as the gateway's records, with the field limits and the page snapshot.
- [x] `RestSharePointGateway` on `GraphServiceClient`; the per-request redirect option for `/content`.
- [x] `memoryos.sharepoint.content-timeout` (120 s) for a download.
- [x] `RestSharePointGatewayTest`: the 19 cases, and five new ones.
- [x] ADR 0025 amended; conventions, backend guide, `ARCHITECTURE.md`, connector test matrix, roadmap.
- [ ] A synchronization of a SharePoint source against a real Tenant; staging has none (carried over, see the end).

## Pull request 2: Google Drive on Google's libraries

- [x] Probe: `google-http-client` without redirects and retries and with the failed body left to an interceptor;
  `google-auth-library` loses a rotated refresh token and cannot be kept from a redirect, so it is not used.
- [x] `GoogleTransport`: the initializer and the guarded exchange (bound, failed body to 8 KiB, deadline).
- [x] `RestGoogleDriveGateway` on the Drive, Sheets, Docs and Admin Directory clients, answers parsed by the strict
  reader; `GoogleRefreshTokenRequest`, and `JsonWebSignature` with a `TokenRequest` for the service account.
- [x] `RestGoogleDriveAccountClient` on `GoogleAuthorizationCodeTokenRequest` and the Drive client; the ID token
  still verified by Nimbus over a bounded key set; a typed not-configured failure; one deadline for the consent.
- [x] The 25 and 5 existing cases as the parity suite, with seven new ones; `GoogleDriveOAuthTest` unchanged.
- [ ] Staging: one synchronization of the Drive source and one reconnect after deployment.

## After both

- [x] Staging: every Google Drive source synchronized on the new release, with the results of the earlier one
  ([verification](verification.md)).
- [x] Close-out (owner, 2026-10-04): the increment moved to `completed/` and the roadmap reconciled, with the review
  of what follows left for later.

Not done, and not part of this increment any more:

- A changed Docs, Sheets and binary file acquired through the new Drive client on staging, and a Google account
  consent there.
- A synchronization of a SharePoint source through the Graph SDK against a real Tenant; staging has no such source.
- The status mapping of `PaddleOcrVlClient` aligned with the two gateways, and its plain-HTTP rule.
- Downloads held in memory up to 100 MiB by both gateways.
- The file that fails on every run of one staging Drive source (`SOURCE_ACQUISITION_INTERNAL`), which predates this
  increment.
