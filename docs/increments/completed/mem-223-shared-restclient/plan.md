# Implementation plan

Three pull requests (owner, 2026-10-03: few pull requests). Each preserves behaviour except what the
[design](design.md#behaviour-that-changes) names. All three are implemented; the staging checks and two documents-only items are open.
Paths are under `core/src/main/java/io/memoryos/` unless they say otherwise.

Why three and not fewer:

1. **The shared layer and its plain callers.** What [MEM-198](https://linear.app/memory-os/issue/MEM-198) waits for,
   and where the increment can still end. It ships with real callers, so no unused abstraction is merged.
2. **Voice.** The adapter interfaces change once, with every Voice call that makes the change possible.
3. **Code Interpreter and Web.** The one part that could have been dropped whole without touching the other two.

`OutboundHttp` grows with its callers: `builder(Limits)` in the first pull request, `service` in the second,
`builder(Limits, ResponseErrorHandler)` in the third.

## 0. Scope

- [x] Increment created 2026-10-03; Linear [MEM-223](https://linear.app/memory-os/issue/MEM-223).
- [x] Aim set by the owner 2026-10-03: high-level interfaces over hand-written HTTP, JSON and multipart code.
- [x] Validated against the code and the Spring sources 2026-10-03; findings in the design.
- [x] Decisions 1 to 4 of the design accepted with the owner's go-ahead, 2026-10-03.
- [x] This directory and its roadmap row travel with the first pull request; there is no pull request for the
  documents alone.

## Pull request 1: the shared layer, Image, OIDC discovery and the model list

Unblocks step 3 of MEM-198. The shared layer was written and tested first; the callers followed in the same branch.

### The shared layer

- [x] `core/build.gradle.kts`: `api(libs.spring.web)`, because `RestClient.Builder` is in `OutboundHttp`'s signature.
- [x] New `shared/OutboundHttp.java`: `Limits`, `builder(Limits)`, `ResponseTooLargeException`. One static JDK
  `HttpClient`: `Redirect.NEVER`, 5 s connect timeout, HTTP/1.1. A `JdkClientHttpRequestFactory` per builder with
  `setReadTimeout(limits.timeout())` and `enableCompression(false)`. A status handler for every status but 2xx that
  throws `RestClientResponseException` with the status and reads nothing. Converters named through
  `configureMessageConverters`, not detected: bytes, text, form and multipart, and Jackson 3 JSON.
- [x] New `shared/BoundedRequestFactory.java`, package-private, extending `AbstractClientHttpRequestFactoryWrapper`.
  The request delegates every method and implements `StreamingHttpOutputMessage`. The response refuses a
  `Content-Length` over the bound, wraps the body in a stream that throws `ResponseTooLargeException` past the bound,
  and closes the body before the delegate.
- [x] `shared/package-info.java`: the kernel's description names outbound HTTP.
- [x] New `core/src/test/java/io/memoryos/shared/OutboundHttpTest.java`, against a local `HttpServer`, nine cases:
  - a 302 fails with its status and the target receives no request;
  - a 500 with a body that never ends fails at once, with no body on the exception, and the server sees the
    connection close;
  - a body at the bound is read, and one byte over fails, with and without a declared length;
  - a body that never ends fails at the bound and the server sees the connection close;
  - `exchange` that reads 256 bytes of a body that never ends closes the connection without reading the rest;
  - a body that stalls fails at the deadline;
  - an upload is streamed: the server reads the first part while the body still has a part to write;
  - JSON is written and read through Jackson 3, and no compression is asked for.
- [x] The result of the never-ending-body cases recorded in the design (finding 12): the stop condition held.

### Image

Changed from the plan while implementing (design, finding 11): `ImageHttp` stays as the bean
`ImageProviderClientTest` replaces.

- [x] `chat/image/ImageHttp.java` rewritten on a client from `OutboundHttp.builder(new Limits(120 s, 20 MiB))`: `post`
  sends the JSON text; `postMultipart` uses `MultipartBodyBuilder`, text fields in insertion order as UTF-8 text and
  files as named parts with their content type. A failed answer is its status with no body read. A response over
  the bound is still `IOException("Image response too large")`. The Apache pool, `@PreDestroy` and `AutoCloseable`
  go; 82 lines become 58.
- [x] `ImageCall`, `ImageProviderClient`, both adapters and `ImageProviderClientTest` unchanged.
- [x] New `ImageHttpTest` with a local server: JSON and its headers, the multipart parts on the wire, a failed
  answer unread, a redirect not followed, a response over 20 MiB, an unreachable provider.

### OIDC discovery and the model list

- [x] `iam/keycloak/OidcDiscoveryClient.java`: the request on `OutboundHttp.builder(new Limits(10 s, 64 KiB))`; the
  constructor taking an `HttpClient` goes; any status but 200 is still a discovery failure; a document over the
  bound is a discovery failure; the other messages are unchanged.
- [x] New `OidcDiscoveryClientTest` with a local server, six cases: a matching document, another issuer, a missing
  endpoint or a body that is not JSON, a body over 64 KiB, a status other than 200 and a redirect, an issuer that
  cannot be reached.
- [x] `ai/openai/OpenAiProviderAdapter.java` `reportedModels`: `exchange` on a client built with the call's timeout
  and the 16 MiB bound; 401 and 403, 5xx and the rest map as before. The body is read as bytes and parsed by the
  Jackson 2 mapper this class uses.
- [x] `ai/openai/LocalModelMetadata.java`: `enrich` and `read` take the `RestClient` and no timeout; `read` still
  returns `null` for anything but 200, an empty body or one over 4 MiB, and no longer reads a failed answer.
- [x] `OpenAiProviderAdapterTest` passes unchanged; `LocalModelMetadataTest` passes a `RestClient`.

### Documents and checks

- [x] The order of choice and the four transport rules in [conventions](../../../conventions.md) and the
  [backend guide](../../../guidelines/backend.md); [ADR 0025](../../../decisions/0025-outbound-http-through-the-highest-level-client.md);
  [ARCHITECTURE.md](../../../../ARCHITECTURE.md) names the shared layer; the identity spec and the identity and chat
  test matrices name the new bound and tests.
- [x] [verification.md](verification.md) started: commands, results, lines removed and added.
- [ ] `clean check` in CI.
- [ ] Staging, once deployed: an image generated and edited, with OpenAI and with Cloudflare; an identity provider
  added; a model list fetched from OpenRouter and from a local server.

## Pull request 2: Voice

Stacked on pull request 1. The OpenAI SDK check came first, then Soniox, the single calls and the adapter interfaces.

### OpenAI transcription

- [x] Probe, with no production change: `TranscriptionCreateParams.file(MultipartField<InputStream>)` streams the
  upload and carries the file name and content type, but two answer shapes of compatible servers fail in the SDK's
  typed answer (design, finding 15). The second check failed.
- [x] So `voice/OpenAiAudio.java` `segments` is on `RestClient` with `MultipartBodyBuilder` and the audio part;
  `segments(JsonNode)` stays; the hand-written multipart and `field` go.
- [x] New `OpenAiAudioTest` with a local server: the recording streamed as one `file` part after its fields, a bearer
  value without a key, a rejected upload reported without its payload.

### Soniox

- [x] `shared/OutboundHttp.java`: `service(Class<T>, RestClient)`, with its case in `OutboundHttpTest`; one executor
  of virtual threads shared by its request factories (design, finding 14); JSON read whatever the answer's content
  type (finding 16), with its case in `OutboundHttpTest`.
- [x] New `voice/SonioxApi.java`: six methods (`upload`, `transcribe`, `status`, `transcript`, `deleteTranscription`,
  `deleteFile`) and two records. The transcript stays a tree.
- [x] `voice/SonioxAsync.java`: `run` builds one proxy per transcription from
  `OutboundHttp.builder(limits).baseUrl(baseUrl).defaultHeaders(h -> h.setBearerAuth(key))`. `upload`, `post`, `get`,
  `send`, `delete`, `encode` and `safe` go; polling, clean-up, `group` and `asyncModel` stay. 203 lines become 167.
- [x] `voice/AudioSource.java`: `part(source, size, filename)`, a `Resource` read from its source as the part is
  written, under a name that cannot break the part header; `body` goes.
- [x] One bound for the client, 64 MiB (the cap `PaddleOcrVlClient` has), sized for a long recording's transcript.
- [ ] The bound checked against the longest recording on staging.
- The upload declares no length. It is not a fallback case: no provider's endpoint refuses such a body
  ([probe](verification.md#a-multipart-body-without-a-declared-length)).

### ElevenLabs, Azure and the connection checks

- [x] `voice/ElevenLabsVoice.java`: `transcribe` on `RestClient` with `MultipartBodyBuilder` (`model_id`,
  `language_code`, `file` named `audio.wav` as `audio/wav`); the `field` helper goes. `speech` is untouched.
- [x] `voice/AzureSpeech.java`: `transcribe` posts each part through `RestClient`, the body written as bytes with its
  length because Spring cannot parse the content type Azure asks for (finding 17); the JSON stays a tree.
- [x] `voice/VoiceChecks.java`: `jsonListing` on `retrieve()`; `arrayListing` on `exchange`, still reading 256 bytes,
  on a client without a length limit because the Azure voice list is large; the static `HttpClient` and the two
  catch ladders go.
- [x] Bound: 1 MiB, the value `VoiceChecks.MAX_LISTING_BYTES` had, for clip answers and JSON listings.

### The adapter interfaces

- [x] `VoiceAdapter.transcribe` and `BatchTranscriptionAdapter.segments` lose their `HttpClient` parameter and
  `IOException`, in the five adapters. `VoiceTranscriptionService.HTTP` and `BatchTranscriptionService.HTTP` go.
  `SpeechSynthesisAdapter.speech` and `VoiceSynthesisService.HTTP` are unchanged.

### Tests and checks

- [x] `SonioxAsyncTest`: the six calls in order; the upload as one `file` part with its file name and content type,
  streamed and without a declared length; both deletes after a failure; a rejected upload leaves nothing to delete.
- [x] `ElevenLabsVoiceTest` (parts asserted on the wire), `AzureSpeechTest`, `VoiceProviderClientTest`,
  `VoiceTranscriptionServiceTest` and `BatchTranscriptionTest` pass; `GuardedAudio` is a fixture of its own.
- [ ] `clean check` in CI.
- [ ] Staging, once deployed: a dictation clip with each Voice provider; the longest recording available with Soniox;
  a recording through OpenAI and through an OpenAI-compatible server; a connection check of each provider.

## Pull request 3: Code Interpreter and Web

### Code Interpreter, moved whole

- [x] `shared/OutboundHttp.java`: `builder(Limits, ErrorHandler)` for an API whose failed answers must be read.
- [x] `shared/OutboundHttp.java`: two JDK clients chosen by the URL's scheme, HTTP/2 over `https` and HTTP/1.1
  over `http`; `OutboundHttpTest` asserts the plain side on the wire (HTTP/1.1, no `Upgrade` header) and the
  choice for each scheme.
- [x] New `chat/interpreter/InterpreterApi.java`: `upload`, `execute`, `download`, `delete`. `execute` answers a tree,
  read by the code that reads the stream's result event.
- [x] `chat/interpreter/InterpreterClient.java`: the Apache client, `request`, `send`, `Response`, `Handler` and both
  `requireSuccess` go. One handler reads at most 2 KiB of a failed body, throws `BusyException` on 429 and lets a 404
  on `DELETE` pass. A client is built per call with the call's deadline and bound. Health and `executeStream` run on
  `exchange`; `consume` and `readLine` stay. One method translates what `RestClient` throws back into `IOException`,
  `BusyException` and `TooLargeException`; `RunPythonTool`, `PresentationPreviewService` and
  `ChatInterpreterController` are untouched. The class no longer needs closing.
- [x] `InterpreterClientTest` passes with its assertions unchanged; added: a run its listener stops makes the server
  see the connection close.
- [ ] Staging: Python run with a file in and a file out; a run stopped mid-output frees its slot.

### Web

- [x] `chat/web/WebHttp.java`: `provider` on a client from `OutboundHttp.builder(new Limits(15 s, 2 MiB))`; the
  Apache client serves the page reader only. `WebCall`, the adapters and their tests are unchanged.
- [x] `WebHttpTest` and `WebProviderClientTest` pass unchanged.
- [ ] Staging: a Web search with two providers; a page opened.

## Closed on 2026-10-04 (owner)

- [x] The staging checks of all three pull requests, recorded in `verification.md`: the connection tests, the model
  list, OIDC discovery, a Python run with a file in and a file out, a run stopped mid-output, a page opened.
- [x] Close-out: the increment moved to `completed/`, links to it fixed and the roadmap reconciled.

Not done, and not part of this increment any more; each is a follow-up of its own if it is wanted:

- **MCP OAuth spike** (optional): Nimbus `oauth2-oidc-sdk` for the token and registration messages of
  `McpOAuthProtocol`, keeping the bound and the no-redirect rule.
- **`sources` assessment**: `RestSharePointGateway`, `RestGoogleDriveGateway`, `PaddleOcrVlClient` and
  `RestGoogleDriveAccountClient` read in full and given one verdict each by the order of choice. Known already:
  `BoundedDoclingClient` is choice 1; `RestGoogleDriveAccountClient` would gain close without draining, but hands its
  request factory to a `RestTemplate` for `NimbusJwtDecoder`.
- **On staging**: an authorized upload (a dictation clip and a long recording with Soniox, an image edit), the Voice
  providers staging does not have, and that a provider answers over HTTP/2.

`clean check` ran in CI for each pull request.
