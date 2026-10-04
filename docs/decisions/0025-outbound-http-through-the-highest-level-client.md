# 25. Outbound HTTP goes through the highest-level client that fits, over one bounded transport

Date: 2026-10-03

## Status

Accepted; implementation started 2026-10-03 in
[outbound HTTP through high-level interfaces](../increments/completed/mem-223-shared-restclient/design.md)
([MEM-223](https://linear.app/memory-os/issue/MEM-223)): the transport, Image, OIDC discovery and the model list.
Voice and the Code Interpreter follow in the same increment.

## Context

`core` called external HTTP APIs through whatever each capability built for itself: Apache HttpClient transports
(Web, Image, the Code Interpreter), JDK `HttpClient` requests (Voice, the model list, OIDC discovery, MCP OAuth) and
vendor SDKs. Each hand-built transport repeated the same protections (no redirect followed, a bounded response, a
failed body left unread, a timeout), and five calls had no bound at all. Three files framed a multipart body by
concatenating a boundary by hand.

An interface someone else maintains gets the protocol right more reliably than a copy written here. The owner set
the aim on 2026-10-03: use high-level interfaces, and write less HTTP, JSON and multipart code by hand.

## Decision

**Order of choice**, for each external API (not for each capability module):

1. A library that ships a client is used through that client.
2. Otherwise, an API with several endpoints and a fixed shape gets one `@HttpExchange` interface.
3. Otherwise `RestClient` directly: a single call, or JSON whose shape differs by server.
4. A hand-built client remains only for WebSocket, for a response the caller cancels while it is being sent, and
   for the public page reader's DNS check.

**Transport rules**, for choices 2 and 3 and for a library that accepts a `RestClient.Builder`: the client comes from
`io.memoryos.shared.OutboundHttp`, which

- never follows a redirect, so a credential is not sent on to another host;
- ends an exchange at its deadline, counted from sending the request to closing the response;
- stops reading a response at its bound, and closes a response without reading the rest of it;
- reports any status but 2xx by the status alone, without reading the body, unless the caller uses `exchange`.

`OutboundHttp` uses one JDK `HttpClient` on HTTP/1.1, names its message converters instead of detecting them, and
does not use Spring Boot's auto-configured builder, so no property relaxes a rule. It lives in the shared kernel
because `iam`, `ai`, `mcp`, `voice` and `chat` depend on `shared` and on no other common module.

## Consequences

- The bound is a request factory decorator, not an interceptor: with an interceptor Spring holds the whole request
  body in memory, which a 500 MB recording upload cannot afford.
- The deadline covers the whole exchange. Calls that timed out per read (Apache) or until the response headers
  (JDK) now have one deadline; a client is built per distinct deadline.
- Voice REST calls, which negotiated HTTP/2, use HTTP/1.1. Left at its default the JDK client would ask every new
  plain-HTTP connection to upgrade to HTTP/2, which the Apache transports never did.
- A streamed multipart body written by Spring declares no length.
- `OutboundHttp` grows with its callers; a method is added with the first call that needs it
  ([ADR 0002](0002-no-speculative-operational-surfaces.md)).
- `OutboundHttpTest` holds the transport rules, including a response that never ends.

## Amendment, 2026-10-04

The HTTP version follows the scheme of the URL: `https` may use HTTP/2, agreed in the TLS handshake, and `http`
stays on HTTP/1.1, so no upgrade request is sent to a self-hosted server. This replaces "one JDK `HttpClient` on
HTTP/1.1" above and the consequence about Voice: Voice keeps HTTP/2, and a body without a declared length is
chunked only over plain HTTP.

## Amendment, 2026-10-04: a library with its own HTTP stack

The transport rules above name `OutboundHttp`, which a library can use only when it accepts a `RestClient`. A vendor
SDK that brings its own HTTP stack is still the first choice (owner, 2026-10-04: a maintained SDK before hand-written
protocol code, judged by quality and maintenance, not by effort or size). It keeps the four rules by configuring that
stack: its redirect following and its retries are switched off or narrowed to what a request asks for, its responses
are bounded, a failed answer is reported by its status with the body unread, and the exchange has a deadline. Each
rule is proven by a test on the wire, as it is for `OutboundHttp`. The first case is the Microsoft Graph SDK on OkHttp
([MEM-226](../increments/completed/mem-226-sources-vendor-sdks/design.md), `GraphTransport`).
