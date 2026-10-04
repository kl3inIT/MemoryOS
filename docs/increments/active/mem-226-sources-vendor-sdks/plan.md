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
- [ ] Staging: one synchronization of the SharePoint source after deployment.

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

- [ ] The status mapping of `PaddleOcrVlClient` aligned with the two gateways.
- [ ] Close-out.
