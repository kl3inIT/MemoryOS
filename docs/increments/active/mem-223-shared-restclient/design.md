# Outbound HTTP through high-level interfaces (MEM-223)

Status: accepted 2026-10-03, after validation against `main` at `dca7609d`, Spring Framework 7.0.9 sources and
`openai-java` 4.49.0. Pull request 1 (the shared layer, Image, OIDC discovery, the model list) is implemented; Voice
and the Code Interpreter have not started. Decision: [ADR 0025](../../../decisions/0025-outbound-http-through-the-highest-level-client.md).
Linear: [MEM-223](https://linear.app/memory-os/issue/MEM-223). The first pull request comes before step 3 of
[MEM-198](https://linear.app/memory-os/issue/MEM-198), which hands the builder made here to the TypeSafe SDK.

## Aim (owner, 2026-10-03)

Less HTTP, JSON and multipart code written by hand, because an interface someone else maintains gets the protocol
right more reliably than a copy written here. Whether a client instance is shared is a means, not the aim.

The unit of the choice is one external API, not one capability module: `voice` calls four vendors and makes four
choices. For each external API, the order of choice is:

1. A library that ships a client is used through that client (OpenAI, AWS, Keycloak, OpenSearch, Docling, TypeSafe).
2. Otherwise, when the API has several endpoints and a fixed shape, one `@HttpExchange` interface. Two APIs qualify
   today: Soniox async transcription (six calls) and the Code Interpreter service (five).
3. Otherwise `RestClient` directly: a single call, or JSON whose shape differs by server.
4. A hand-built client remains only for WebSocket, for a response the caller cancels while it is still being sent
   (streamed speech), and for the public page reader's DNS check.

What an `@HttpExchange` interface buys is not the grouping. Spring writes the URL, the headers, the JSON and the
multipart body from typed arguments, so nobody concatenates `"--" + boundary + "\r\nContent-Disposition…"` (written
three times in `voice` today). For a single call, an interface and its records are more code than the call.

## How the layers fit

An `@HttpExchange` interface, like an SDK, describes an API; it does not replace the HTTP client underneath. Spring
generates a proxy that calls a `RestClient`, and the `RestClient` calls a request factory over the JDK `HttpClient`.
The four transport rules every outbound call keeps (no redirect followed, bounded response, failed bodies unread, a
timeout) are separate from the order of choice above. They belong to the bottom layers, so they are built once and
every interface and SDK sits on them.

```
@HttpExchange interface  or  SDK (TypeSafeClient)      what the API looks like
RestClient                                             conversion, status handling
BoundedRequestFactory                                  response bound, close without draining
JdkClientHttpRequestFactory on one JDK HttpClient      connection, no redirect, timeout
```

## What is written by hand today

As of `dca7609d`, before pull request 1. Every file named here was read in full for this design unless marked.

| Where | Hand-written |
| --- | --- |
| `voice.SonioxAsync` (203 lines) | Six calls built with `HttpRequest.newBuilder`; a multipart body with a hand-written boundary whose file part is streamed with a known length; JSON read by path |
| `voice.ElevenLabsVoice` (67) | One multipart body with a hand-written boundary (speech-to-text) |
| `voice.OpenAiAudio.segments` | A third hand-written multipart body, streamed, beside two OpenAI SDK clients in the same file |
| `voice.AzureSpeech.transcribe`, `voice.VoiceChecks` | A JDK request per audio part; two listing checks, one of which reads only the first 256 bytes of a large list |
| `voice` services and adapter interfaces | Four `static final HttpClient` (`VoiceTranscriptionService`, `BatchTranscriptionService`, `VoiceSynthesisService`, `VoiceChecks`); `HttpClient` is a parameter of `VoiceAdapter.transcribe`, `BatchTranscriptionAdapter.segments` and `SpeechSynthesisAdapter.speech` |
| `chat.image.ImageHttp` (82) | An Apache transport: pool, timeouts, multipart, bounded read, cancel |
| `chat.web.WebHttp` (120) | The same transport for search providers; it shares one request method with the page reader |
| `chat.interpreter.InterpreterClient` (324) | An Apache transport; five plain calls with JSON read by path, one of them a streamed multipart upload; the streamed run |
| `ai.openai`: `OpenAiProviderAdapter.reportedModels`, `LocalModelMetadata.read` | A JDK client built per call, status mapping, bounded reads |
| `iam.keycloak.OidcDiscoveryClient` (141) | A JDK request with an unbounded read; field helpers; no test of its HTTP path |
| `mcp.McpOAuthProtocol` (478) | OAuth messages built and parsed by hand over a 17-line bounded `send`; strict JSON (duplicate keys and trailing tokens refused) and a length limit per field |
| `sources` (read in part) | `PaddleOcrVlClient`, `RestSharePointGateway` and `RestGoogleDriveGateway` on the JDK client; `BoundedDoclingClient` on the Docling SDK with a bounded transport; `RestGoogleDriveAccountClient` on `RestClient` |

Transport rules missing today:

- No bound on the response in `OidcDiscoveryClient`, `AzureSpeech.transcribe`, `ElevenLabsVoice.transcribe`,
  `OpenAiAudio.segments` and `SonioxAsync.send`.
- `RestGoogleDriveAccountClient.json` reads at most 64 KiB, but Spring then reads the rest of the body when it closes
  the response, until the 5 s timeout.

## Assessment per place

| Place | Target | Choice | Verdict |
| --- | --- | --- | --- |
| Soniox async | `@HttpExchange` `SonioxApi` | 2 | Clear. Open point: the upload no longer declares its length |
| ElevenLabs speech-to-text | `RestClient` with `MultipartBodyBuilder` | 3 | Clear |
| OpenAI transcription with segments | The OpenAI SDK, `audio().transcriptions()` and `asVerbose()` | 1 | To verify first: the SDK streams the upload; a compatible server's answer still parses |
| Azure short audio, connection checks | `RestClient` | 3 | Small |
| Voice adapter interfaces | `HttpClient` leaves `transcribe` and `segments`, stays on `speech` | | Follows from the four above |
| Image | `ImageHttp` rewritten on `RestClient` with `MultipartBodyBuilder` | 3 | Done: 82 lines become 58 and one Apache pool goes; see finding 11 |
| OIDC discovery | `RestClient`, JSON as a tree | 3 | Done: small; gains the bound and its first HTTP test |
| Model list, local model details | `RestClient.exchange`, JSON as a tree | 3 | Done: small; no JDK `HttpClient` built per call |
| Code Interpreter | `@HttpExchange` `InterpreterApi` for the five calls and the run on `RestClient.exchange`, in one change | 2 | Moderate, all or nothing; see below |
| Web search providers | `WebCall.json` on `RestClient` | 3 | No lines saved; optional |
| MCP OAuth | Unchanged; a library only after a spike | | Uncertain |
| `sources` | Not assessed here | | Read and judged in the last pull request; no code |
| System One | `TypeSafeClient` over `OutboundHttp.builder` | 1 | [MEM-198](https://linear.app/memory-os/issue/MEM-198) |

Considered and not proposed:

- **OpenAI Images through the OpenAI SDK.** The adapter is 62 lines, and `ImageHttp` bounds a 20 MiB response that
  the SDK would not. Voice differs: `OpenAiAudio` already calls the provider through the SDK for dictation and speech,
  and the transcription it would add has no bound today.
- **`@HttpExchange` for the eight search vendors.** An adapter is `call.json(...)` followed by `WebCall.results(...)`;
  an interface and records per vendor would be more code.
- **Typed records for the Soniox transcript.** `SonioxAsync.group` reads each token with a default per field; a record
  with seven nullable components is longer than the tree. The transcript stays a `JsonNode`.
- **A record for the OIDC discovery document.** A non-string field is treated as absent today, and three fields are
  required non-blank; a record would coerce the first and still need the checks. The document stays a tree.
- **One observation helper for Web, Image and Voice.** The three wrappers are not the same shape (a closure in two,
  start and stop apart in Voice) and are not HTTP. They stay as they are.

### Code Interpreter: all or nothing

By shape this is the best case for an interface: five endpoints of a service MemoryOS owns. The hand-built client
also carries requirements the interface has to keep:

- A timeout per call (5 s for health, 10 s for delete, 30 s for upload and download, the run's own timeout plus
  10 s), where Spring's JDK factory has one per factory. A proxy is built per timeout.
- A failed body is read, up to 2 KiB, and reaches the model (Onyx parity); 429 is `BusyException`; a 404 on delete is
  not a failure.
- Two bounds: 8 MiB for JSON, 25 MiB for a download, where exceeding it is `TooLargeException`.
- Callers catch `IOException`, and `BusyException` and `TooLargeException` extend it. `RestClient` throws unchecked
  exceptions (an `IOException` from a status handler arrives as `UncheckedIOException`), so the class translates
  them back; `health` turns a failed status into a `Health` instead of throwing.

Moving only the five plain calls would leave two HTTP stacks in one class. So the class moves whole, including
`executeStream` onto `RestClient.exchange`, or it stays. `executeStream` can move only if closing the response
cancels the exchange, which `OutboundHttpTest` proves or disproves in the first pull request.

### MCP OAuth and a library

`McpOAuthProtocol` already keeps the four transport rules. About 50 of its 478 lines are HTTP plumbing; the rest is discovery
(RFC 9728, RFC 8414), registration (RFC 7591), PKCE, the token exchanges and validation that is MemoryOS's own: a
strict JSON reader and a length limit on every field. Putting `send` on `RestClient` would save nothing.

Nimbus `oauth2-oidc-sdk` 11.38.2 is on the API's classpath through Spring Security; `core` does not declare it. It has
`AuthorizationServerMetadata`, `ClientRegistrationRequest`, `TokenRequest` and `TokenErrorResponse`; it has no class
for RFC 9728, and it would not apply the strict reader or the field limits. A spike decides; leaving the class as it
is counts as a good outcome.

## Findings of the validation (2026-10-03)

What reading the code and the Spring sources changed in this design:

1. **The bound cannot be an interceptor.** `InterceptingClientHttpRequest` extends
   `AbstractBufferingClientHttpRequest`: with any interceptor the whole request body is held in memory, which would
   undo the streamed upload of a 500 MB recording (`f76c9c5c`). The bound is a request factory decorator instead.
2. **Spring's multipart does not declare a length.** `FormHttpMessageConverter.writeMultipart` streams and sets no
   `Content-Length`, so over HTTP/1.1 the upload is chunked. Today's Soniox and OpenAI uploads declare their length
   on purpose (`AudioSource.body`). Whether each provider accepts the change is checked on the wire and on staging.
3. **The read timeout is one deadline.** `JdkClientHttpRequest.TimeoutHandler` counts from sending until the body is
   closed and closes the body when it passes. It is set per factory, so a client is built per distinct timeout.
4. **Draining.** `JdkClientHttpResponse.close()` drains, then closes. A body closed first makes the drain fail at
   once and be ignored, so the decorator closes the body before the response. A test still has to show it.
5. **Compression is on by default** in `JdkClientHttpRequestFactory`. It is switched off, so requests keep the
   headers they have today and `Content-Length` means the bytes the bound counts.
6. **Spring's default error handler reads the failed body** into its exception. The builder installs a handler that
   reads nothing.
7. **The OpenAI SDK does return segments.** `TranscriptionCreateResponse.asVerbose()` and `TranscriptionSegment`
   (`start`, `end`, `text`, `avgLogprob`) exist; the comment in `OpenAiAudio` is about Spring AI's transcription
   model, which returns text only.
8. **Web saves no lines.** `WebHttp.provider` is three lines over a request method the page reader keeps.
9. **`sources` holds four more hand-built clients** that the first draft did not name.
10. **No observation registry in the builder.** Today's transports record no `http.client.requests`; a caller that
    wants it sets it on the builder it receives.

Found while implementing pull request 1:

11. **`ImageHttp` stays, as the bean tests replace.** `ImageProviderClientTest` asserts the URL an adapter builds,
    including the public default (`https://api.openai.com/v1`), which a local server cannot receive. Deleting
    `ImageHttp` would have needed a constructor only tests call. So the class keeps its two methods and is rewritten
    on `RestClient`; `ImageCall`, the adapters and `ImageProviderClientTest` are unchanged, and `ImageHttpTest` asserts
    the wire.
12. **The stop condition held.** A response that never ends fails 5 ms after the bound is passed and the server sees
    the connection close 2 ms later, with a 60 s deadline still far away (`OutboundHttpTest`).
13. **`RestClient` wraps what the transport throws.** An `IOException` raised while a response is read, the bound
    included, arrives as `ResourceAccessException` with the `IOException` as its cause, and Spring's message repeats
    the request URL. Callers map by the cause and never pass that message on.
14. **A streamed request body is written on a thread of its own.** `JdkClientHttpRequestFactory` takes the JDK
    client's executor, and without one creates a `SimpleAsyncTaskExecutor`: one new thread for each request whose
    body is streamed. That is harmless for an image edit or an Ollama detail read. Before the Voice uploads move
    (pull request 2), `OutboundHttp` gets one executor shared by its factories.

## The shared layer

`io.memoryos.shared.OutboundHttp`, in the shared kernel because `iam`, `ai`, `mcp`, `voice` and `chat` may all depend
on `shared` and on no other common module. `BoundedRequestFactory` sits beside it, package-private.

```java
public final class OutboundHttp {
    public record Limits(Duration timeout, int maxResponseBytes) {}

    /** No redirect, the timeout, a bounded response, and a non-2xx answer fails with its status, body unread. */
    public static RestClient.Builder builder(Limits limits);

    /** The same, for an API whose failed answers must be read: failed receives them, within the bound. */
    public static RestClient.Builder builder(Limits limits, ResponseErrorHandler failed);

    /** An @HttpExchange proxy over a client built here. */
    public static <T> T service(Class<T> api, RestClient client);

    /** The bound was exceeded; an IOException a caller can tell from other failures. */
    public static final class ResponseTooLargeException extends IOException {}
}
```

A caller adds what is its own on the builder: the base URL and the credential header of one connection, or nothing.
A library that takes a `RestClient.Builder` (`TypeSafeClient`) receives the builder itself. Each method is merged
with its first caller: `builder(Limits)` with Image, `service` with Soniox, the handler overload with the Code
Interpreter.

- **One JDK `HttpClient`** for all callers: `Redirect.NEVER`, a 5 s connect timeout, HTTP/1.1. Spring Boot's
  auto-configured builder is not used, so no property can relax the transport rules and unit tests need no
  application context.
- **HTTP/1.1** is what the Apache transports replaced here speak. Left at its default, the JDK client tries to
  upgrade every new plain-HTTP connection to HTTP/2 (`HttpClient.Builder.version`), which the Apache-based calls
  (the Code Interpreter, Image, Web) do not do today; the two `sources` clients that call self-hosted services pin
  HTTP/1.1 as well. The cost is that Voice REST calls, which negotiate HTTP/2 today, use HTTP/1.1.
- **Converters** are set on the builder, not detected: JSON through Jackson 3 (`tools.jackson`), bytes, and the form
  and multipart converter with `Resource` parts. `core` has both Jackson generations on its classpath.
- **Timeout.** `Limits.timeout` is the factory's read timeout, a deadline for the whole exchange.
- **Bound.** `BoundedRequestFactory` extends `AbstractClientHttpRequestFactoryWrapper`. Its request passes
  everything through, including `StreamingHttpOutputMessage.setBody`, so a streamed upload stays streamed. Its
  response refuses a `Content-Length` over the bound, fails with `ResponseTooLargeException` once more than the bound
  was read, and closes the body before the response.
- **Failed answers.** Anything but 2xx, a redirect included, throws `RestClientResponseException` carrying the status
  and no body. A caller that must read a failed body passes its own handler (`InterpreterApi`) or uses `exchange`.
- **Stop condition.** If the test cannot show that an endless body stops being read at the bound, nothing moves onto
  this layer and the finding is recorded here. It held (finding 12).

## What stays

- `WebHttp.page`: the DNS resolver that refuses non-public addresses and validates every redirect hop. The JDK client
  has no resolver hook.
- Streamed speech: `HttpAudioStream`, `ProviderSpeech.http`, `ElevenLabsVoice.speech` and the client in
  `VoiceSynthesisService`. The request is cancelled while it waits for headers, which `RestClient` cannot do.
- The WebSocket transcribers and their clients.
- The vendor SDK clients for chat models, embeddings, S3, OpenSearch, Keycloak, Docling and MCP tool calls.
- The observations `memoryos.chat.web.request`, `memoryos.chat.image.request` and `memoryos.chat.voice.request`.

## Behaviour that changes

- Responses that had no bound get one: OIDC discovery at 64 KiB (the bound `McpOAuthProtocol` uses for the same kind
  of document; measured documents are 1.2 to 8.9 KB); Voice answers at a bound set from measured responses in the
  Voice pull request and recorded here.
- The timeout of a moved call is a deadline for the whole exchange. Today the JDK calls time out until the response
  headers arrive, and the Apache calls time out per read.
- A streamed multipart upload no longer declares its length (finding 2).
- A discovery document over its bound is `DISCOVERY_FAILED`. An image response with an empty 2xx body fails as "no
  image" from the adapter instead of "Empty image response" from the transport; both are an `IOException`.
- The model list request has one deadline of the caller's timeout and connects within 5 s, where the caller's
  timeout applied to the connection and to the wait for headers separately.
- Voice REST calls use HTTP/1.1 where the JDK client negotiated HTTP/2, the cost of one client that never attempts
  an HTTP/2 upgrade on plain HTTP.
- Nothing else: messages, status mapping, metric names and the REST contract are unchanged.

## Decisions

The owner had these four proposals before them and answered "làm đi" on 2026-10-03, without changing any; they are
carried as accepted.

1. `OutboundHttp` in the shared kernel, which adds outbound HTTP to what the kernel holds.
2. OIDC discovery bounded at 64 KiB.
3. The optional parts: the MCP OAuth spike is kept; Web moves only if the Code Interpreter moved, when it leaves
   Apache HttpClient with one job.
4. The shared client speaks HTTP/1.1, which moves Voice REST calls off HTTP/2.

Also decided 2026-10-03: three pull requests, not one per place ([plan](plan.md)); probes and spikes are welcome
wherever a claim needs one.

## Verification

- `OutboundHttpTest`, against a local `HttpServer`: a redirect is returned as a failure, not followed; a body over
  the bound stops the read, including from a server that never stops sending; a failed body is unread by default and
  readable within the bound by a handler; the deadline ends a stalled body; a streamed upload is not buffered.
- Each family keeps its existing tests. Those that already stub HTTP with a local `HttpServer` (Voice,
  `InterpreterClientTest`, `LocalModelMetadataTest`, `OpenAiProviderAdapterTest`) keep doing so. A test that replaces
  a transport bean (`ImageProviderClientTest`) keeps it, and the bean gets its own test on the wire
  (`ImageHttpTest`). No constructor is added for tests.
- Multipart requests are asserted on the wire: part names and order, filename, part content type.
- `ModulithArchitectureTest` and `CoreDependencyRulesTest` pass; `openapi.yml` does not change.
- Staging, per step: a clip and a recording transcribed with Soniox; a clip with ElevenLabs and with Azure; a
  recording with an OpenAI-compatible server; an image generated and edited; an identity provider added; a model
  list fetched; Python run with a file in and a file out.
- Each pull request reports the lines removed and added.

## Out of scope

System One ([MEM-198](https://linear.app/memory-os/issue/MEM-198)); HTML to Markdown in the page reader; tool groups
([MEM-224](https://linear.app/memory-os/issue/MEM-224)); moving any `sources` client.
