# The `sources` clients on vendor SDKs (MEM-226)

Issue: [MEM-226](https://linear.app/memory-os/issue/MEM-226). Follows
[MEM-223](../../completed/mem-223-shared-restclient/design.md), which moved `core`'s outbound HTTP to the
highest-level client that fits ([ADR 0025](../../../decisions/0025-outbound-http-through-the-highest-level-client.md))
and left four hand-written clients in `sources` unassessed.

## Aim (owner, 2026-10-04)

A maintained vendor SDK before hand-written protocol code, judged by code quality, extensibility and maintenance,
not by effort or dependency size. SharePoint goes through the Microsoft Graph SDK and Google Drive through Google's
API clients. The protections MemoryOS set on those calls stay, on the SDK's own HTTP stack.

## The four clients

| Class | Calls | Decision |
| --- | --- | --- |
| `RestSharePointGateway` | Microsoft Graph, eleven read operations | Microsoft Graph SDK (pull request 1) |
| `RestGoogleDriveGateway` | Drive v3, Sheets v4, Docs v1, Admin Directory v1, the token endpoint | Google's API clients and their OAuth library (pull request 2) |
| `RestGoogleDriveAccountClient` | Google's OAuth endpoints, JWKS, Drive `/about` | Google's authorization-code exchange and Drive client; the ID token stays on Nimbus (pull request 2) |
| `PaddleOcrVlClient` | one `POST /layout-parsing` to a self-hosted server | Stays: no client library exists; its transport already matches `OutboundHttp` |

An earlier assessment in the issue kept the first two hand-written, on the grounds that the SDKs follow redirects,
retry and read any response whole, and that the Graph SDK's jar is 64.8 MB. The owner rejected size and effort as
reasons. What remains true of that assessment is the list of protections an SDK does not bring; the design below
keeps each of them.

## What an SDK gives and what it does not

It gives the protocol: request paths and their encoding, OData parameters, typed models that follow the API,
paging links, the bearer token attached only to the hosts it is told. A hand-written `encodePath` that left `/` and
`:` unescaped in identifiers is the kind of code it replaces.

It does not give MemoryOS's transport rules. By default the Graph SDK follows redirects, retries throttled and
failed answers with its own backoff, reads a response whole and parses a failed body into an error model. Each
default is replaced, and each replacement has a test on the wire.

## SharePoint on the Microsoft Graph SDK

`microsoft-graph` 6.70.0. It runs on OkHttp 4.12, which `core` already has, and on Kiota's request adapter.

### Transport (`GraphTransport`)

The `OkHttpClient` handed to `GraphServiceClient` is built from the SDK's factory with three interceptors of ours and
the SDK's choosing instead of the default set:

| Rule | How |
| --- | --- |
| No redirect | OkHttp's own following is off; the SDK's `RedirectHandler` is installed with a default option of zero redirects |
| One redirect for a download | The `/content` request carries its own `RedirectHandlerOption`: one hop, only when the target is the Tenant host, and the `Authorization` header removed. This is the SDK's mechanism, configured per request |
| No retry | The SDK's `RetryHandler` is not installed and OkHttp's connection retry is off. Retrying stays in `core` (`SourceSyncEngine`), which reads `Retry-After` from the failure |
| Bounded answer | A guard interceptor refuses a declared length over the bound and wraps the body in a source that fails past it |
| Failed body unread | The guard closes any answer that is not a success and throws its status and `Retry-After`; the SDK's error parsing never runs |
| Deadline | The guard schedules a cancel of the call at the exchange's timeout, released when the body is closed |
| Token only to Graph | `BaseBearerTokenAuthenticationProvider` with an `AllowedHostsValidator` of the configured Graph host; the token source is still `MsalSharePointTokenSource` |

The limits of one request (bound, timeout) travel as a Kiota request option, which the SDK attaches to the OkHttp
request as a tag; the guard reads it there.

The SDK's user-agent handler is not installed either: the guard sets the `User-Agent` Microsoft asks integrators
to send. The SDK's telemetry handler, which the factory adds itself, stays (it names the SDK version in a header).

`azure-identity`, the SDK's usual credential, is not added: `msal4j` is Microsoft's own library, it is what
`azure-identity` wraps, and the classification of Entra error numbers is written against it.

### Requests

Eight of the eleven operations use the SDK's request builders with typed query parameters. Three addresses are still
written out and passed with `withUrl`: a site by its server-relative path, a folder by its path, and a change log
from a timestamp token. Graph addresses the first two by path, which the builders encode as an identifier, and the
builders have no `token` query parameter for the third; their written shape is the one that has run against a
Tenant. Continuation links go through `withUrl` too, which is how the SDK pages, after the existing check that the
link is on the configured Graph host.

### Models (`GraphModels`)

The SDK's typed models become the records of `SharePointGateway` in one place, which keeps what MemoryOS accepts:
required fields, 16,384 characters per field, 8,192 per link, and the page snapshot (text web parts as HTML, other
web parts by their searchable text, 200 parts, 200 texts and 200,000 characters each).

### Behaviour that changes

- A page list is requested as `/pages/graph.sitePage`, the SDK's form of the cast, where the hand-written client sent
  `/pages/microsoft.graph.sitePage`. Graph documents both namespaces.
- A change log without a token is requested as `/drives/{id}/items/root/delta`, where it was `/drives/{id}/root/delta`.
- Identifiers in a path are encoded by the SDK (a comma in a site id becomes `%2C`).
- Only the redirect statuses the SDK's handler knows are followed for a download; any 3xx with a `Location` was.
- A download has its own budget, `memoryos.sharepoint.content-timeout` (120 s, at most 600 s). It shared the 15 s
  budget of a metadata read, in which a file of up to 100 MiB could not finish on a slow link.
- A response whose declared length is over the bound is refused before it is read.
- A JSON answer without `Content-Type` is not read by the SDK and counts as malformed; Graph always sends one. A
  file is read as a stream whatever its type.
- The download address in an item (`@microsoft.graph.downloadUrl`) is still fetched without a token, on the same
  OkHttp client and under the same guard, as a plain request: it is not a Graph API call.

Not changed: the status mapping, `Retry-After` on 429 and 503 only, the budgets of a metadata read, the metrics, the
`User-Agent`, the session contract.

## Google Drive on Google's API clients (pull request 2)

`google-api-services-drive`, `-sheets`, `-docs` and `-admin-directory`, which bring `google-api-client`,
`google-oauth-client` and `google-http-client`.

### What the probe showed

Run against a local server before any code moved:

- By default `google-http-client` follows a redirect (the second address was contacted) and reads a failed body into
  `GoogleJsonResponseException`, whose message carries it.
- A request initializer that turns off redirects, sets zero retries, removes the unsuccessful-response and
  I/O-exception handlers and adds a response interceptor stops both: one request, and the interceptor runs before the
  library parses anything.
- `UserCredentials.refreshAccessToken()` of `google-auth-library` does not hand back a refresh token Google rotated.
- The Drive client downloads content at `{root}/download/{service path}files/{id}?alt=media`.

### Transport (`GoogleTransport`)

| Rule | How |
| --- | --- |
| No redirect, no retry, no backoff | The initializer every request is built with: `setFollowRedirects(false)`, `setNumberOfRetries(0)`, no unsuccessful-response or I/O-exception handler |
| Bounded answer | A successful answer is refused when its declared length is over the bound and read to the bound plus one byte otherwise. `Accept-Encoding` is not sent and the raw stream is read, so nothing is decompressed past the bound |
| Failed answer | The response interceptor reads at most 8 KiB, disconnects and throws the status, `Retry-After` and those bytes. Google names the reason of a refusal in the body (`rateLimitExceeded`, `insufficientPermissions`), which the gateway classifies by; the text is never passed on. A longer body used to fail as a limit; it is now classified by its first 8 KiB |
| Deadline | The exchange runs on a virtual thread and the caller waits until its deadline; then the thread is interrupted, which closes its socket |

The guard sits around the library's `HttpRequest`, not inside an `HttpTransport` of ours as first planned: redirects
and retries are decided in `HttpRequest.execute`, above any transport, and `NetHttpTransport` is final.

### Gateway

- Every Drive, Sheets, Docs and Admin Directory request is built by Google's typed client: its address, parameters,
  field mask and paging token. The hand-written paths, query strings and URL encoding are gone.
- **Answers are read as bytes and parsed by the gateway's strict reader, not into the clients' models.** A native Docs
  or Sheets snapshot is stored as Google sent it, and the strict reader refuses a duplicated key or a trailing token;
  the models would re-serialize the first and accept the second. Metadata, permissions and directory answers go the
  same way, so every existing check on a field's type and length still runs.
- The refresh grant is `GoogleRefreshTokenRequest`, whose answer still carries a rotated refresh token.
- The service-account grant: Google's `JsonWebSignature` signs the assertion (it was signed with
  `java.security.Signature`), and a `TokenRequest` sends it. `ServiceAccountCredentials` is **not** used, against the
  first plan: it builds its own request, which would follow a redirect with the assertion, retry and read the answer
  whole, and it offers no initializer to stop that. `google-auth-library` is therefore not a dependency.
- A base address is split into the client's root and service path. The Sheets, Docs and Directory clients carry the
  API version in each request path, so their root is the configured base without it
  (`https://sheets.googleapis.com/v4` gives the root `https://sheets.googleapis.com/`); a base that does not end in
  the version is taken as the root.
- Unchanged: the budget of an operation (requests, deadline, cumulative snapshot bytes), the Drive version fence, the
  Docs revision fence, the MD5 check, `incompleteSearch`, the shortcut, trash and root rules, every limit and pattern,
  the status and reason mapping, `Retry-After` on a quota refusal and on 503.

### Account consent

- The code is exchanged by `GoogleAuthorizationCodeTokenRequest` and the account read by the Drive client
  (`about.get`).
- The ID token is still verified by Spring Security's Nimbus decoder with the checks written here. Google's verifier
  fetches its certificates with a request that takes no initializer, so it could be neither bounded nor kept from a
  redirect.
- Fixed: the key set is fetched through `GoogleTransport` within 64 KiB and handed to the decoder (it was fetched by an
  unbounded `RestTemplate`); a consent on a deployment that is not configured fails as `GOOGLE_DRIVE_NOT_CONFIGURED`
  instead of an untyped invalid answer; one deadline of 15 s covers the token exchange, the key set and the account
  read. The key set is fetched for each consent rather than cached; a consent is rare.

### On the wire, what changes

- A file's content is requested at the client's download path: `https://www.googleapis.com/download/drive/v3/files/{id}?alt=media`.
- The fields of a token form are in the order Google's library writes them.
- Requests carry the client's own `User-Agent` and `x-goog-api-client` headers; no `Accept: application/json`.
- A Sheets, Docs or Directory base address that does not end in the API version gets the version appended.

## Verification

- `RestSharePointGatewayTest` is the parity suite: it drives the gateway against a local HTTP server and asserts what
  is on the wire. Its 19 cases pass with two changes to the fixture's expectations, both named above (a
  `Content-Type` on successful answers, the cast path). Five cases are new: a content redirect that leaves the
  Tenant host or redirects again; no retry of 429, 503 and 504; a stalled answer cut off at the request timeout while
  a download uses its own; an answer without a declared length stopped once it passes the bound; an item read with
  the download address Graph annotates it with.
- `RestGoogleDriveGatewayTest` is the parity suite for Drive: its 25 cases pass with four changed expectations (the
  order of the fields of a token form, twice; the version prefix of Directory paths; the download path of a file),
  and five are new: no retry or backoff of 429, 500 and 503 nor of a failed token request; a token endpoint redirect
  not followed; a stalled answer cut off at the request timeout; an answer without a declared length stopped at the
  bound; a refusal classified by its reason in a body longer than 8 KiB, without its text.
- `RestGoogleDriveAccountClientTest`: its 5 cases unchanged, and two new: the typed not-configured failure, and the
  key set and the token answer read within a bound. `GoogleDriveOAuthTest` in `api` (7 cases, the whole consent with
  a signed ID token) passes unchanged.
- `:sources:test` as a whole, `SourcesDependencyRulesTest` included; `api` and `worker` compile.

## Not verified

- Against a real Tenant: the three request shapes that changed, and a download through `/content`. Staging has no
  SharePoint source (found 2026-10-04), so this waits for a Tenant to try it on.
- The image size and start-up time with the SDK's jar.
- Google Drive against Google: the download path, the Sheets, Docs and Directory addresses, both grants and a
  consent. On staging the token grant, listing, metadata and permissions match the earlier release
  ([verification](verification.md)); content and the consent are not run there yet.
- The 15 s deadline of a consent has no test of its own; each request's bound and timeout do.

## Out of scope

- Writing to SharePoint or new Graph operations.
- `PaddleOcrVlClient`, apart from the status mapping noted in the issue.
