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
| `RestGoogleDriveGateway` | Drive v3, Sheets v4, Docs v1, Admin Directory v1, the token endpoint | Google API clients and `google-auth-library` (pull request 2) |
| `RestGoogleDriveAccountClient` | Google's OAuth endpoints, JWKS, Drive `/about` | Google's authorization-code flow and ID-token verifier (pull request 2) |
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

## Google Drive (pull request 2)

To be designed with its probes: the four API clients on one `HttpTransport`, `google-auth-library` in place of the
hand-signed JWT and the refresh-token exchange, and Google's authorization-code flow for the account client. The
findings to fix there: an unbounded JWKS fetch, an untyped failure of the exchange, and no deadline for the three
requests of one consent.

## Verification

- `RestSharePointGatewayTest` is the parity suite: it drives the gateway against a local HTTP server and asserts what
  is on the wire. Its 19 cases pass with two changes to the fixture's expectations, both named above (a
  `Content-Type` on successful answers, the cast path). Five cases are new: a content redirect that leaves the
  Tenant host or redirects again; no retry of 429, 503 and 504; a stalled answer cut off at the request timeout while
  a download uses its own; an answer without a declared length stopped once it passes the bound; an item read with
  the download address Graph annotates it with.
- `:sources:test` as a whole, `SourcesDependencyRulesTest` included; `api` and `worker` compile.

## Not verified

- Against a real Tenant: the three request shapes that changed, and a download through `/content`. Staging has a
  SharePoint source; a synchronization there after deployment is the check.
- The image size and start-up time with the SDK's jar.

## Out of scope

- Writing to SharePoint or new Graph operations.
- `PaddleOcrVlClient`, apart from the status mapping noted in the issue.
