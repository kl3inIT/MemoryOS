# Implementation plan

Three pull requests (owner, 2026-10-03: few pull requests). Each preserves behaviour except what the
[design](design.md#behaviour-that-changes) names. Pull request 1 is implemented; the other two have not started.
Paths are under `core/src/main/java/io/memoryos/` unless they say otherwise.

Why three and not fewer:

1. **The shared layer and its plain callers.** What [MEM-198](https://linear.app/memory-os/issue/MEM-198) waits for,
   and where the increment can still end. It ships with real callers, so no unused abstraction is merged.
2. **Voice.** The adapter interfaces change once, with every Voice call that makes the change possible.
3. **Code Interpreter and the close-out.** The one part that may be dropped whole without touching the other two.

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

Order of work in the branch: the OpenAI SDK check, Soniox, the single calls, then the adapter interfaces.

### OpenAI transcription

- [ ] First, by test and with no production change: `TranscriptionCreateParams.file(MultipartField<InputStream>)`
  streams the upload and carries the file name and content type; an answer holding only `text` and one with a
  self-hosted server's extra fields both parse.
- [ ] If both hold: `voice/OpenAiAudio.java` `segments` calls `audio().transcriptions().create(...)` and maps
  `asVerbose().segments()`; the hand-written multipart, `field` and `segments(JsonNode)` go.
- [ ] If either fails: `segments` on `RestClient` with `MultipartBodyBuilder` and the `Resource` below;
  `segments(JsonNode)` stays. Either way no multipart is framed by hand.

### Soniox

- [ ] `shared/OutboundHttp.java`: `service(Class<T>, RestClient)`, with its case in `OutboundHttpTest` (a
  `@RequestPart` arrives as multipart); one executor shared by its request factories, so a streamed upload does not
  start a thread of its own (design, finding 14).
- [ ] New `voice/SonioxApi.java`:

  ```java
  @HttpExchange(accept = "application/json")
  interface SonioxApi {
      @PostExchange(url = "/files", contentType = "multipart/form-data")
      Created upload(@RequestPart("file") HttpEntity<Resource> audio);
      @PostExchange("/transcriptions")
      Created create(@RequestBody Map<String, Object> transcription);
      @GetExchange("/transcriptions/{id}")
      Status status(@PathVariable String id);
      @GetExchange("/transcriptions/{id}/transcript")
      JsonNode transcript(@PathVariable String id);
      @DeleteExchange("/transcriptions/{id}")
      void deleteTranscription(@PathVariable String id);
      @DeleteExchange("/files/{id}")
      void deleteFile(@PathVariable String id);

      record Created(String id) {}
      record Status(String status) {}
  }
  ```

- [ ] `voice/SonioxAsync.java`: `run` builds one proxy per transcription from
  `OutboundHttp.builder(limits).baseUrl(baseUrl).defaultHeaders(h -> h.setBearerAuth(key))`. `upload`, `post`, `get`,
  `send`, `delete` and `encode` go (about 50 lines). `run`'s polling and clean-up, `group`, `asyncModel` and `safe`
  stay.
- [ ] `voice/AudioSource.java`: a `Resource` over a source, its size and its file name, as `OpenAiAudio.NamedAudio`
  already is for bytes; `body` goes. The part's content type travels in the `HttpEntity` headers.
- [ ] One bound for the client, sized for the transcript of the longest recording. Proposed 64 MiB (the cap
  `PaddleOcrVlClient` has); measured on staging and recorded in the design.
- **Falls back if** Soniox refuses an upload that declares no length: the upload alone sends its hand-framed body
  through the same client with `Content-Length` set; the other five calls still move.

### ElevenLabs, Azure and the connection checks

- [ ] `voice/ElevenLabsVoice.java`: `transcribe` on `RestClient` with `MultipartBodyBuilder` (`model_id`,
  `language_code`, `file` named `audio.wav` as `audio/wav`); the `field` helper goes. `speech` is untouched.
- [ ] `voice/AzureSpeech.java`: `transcribe` posts each part through `RestClient`; the JSON stays a tree.
- [ ] `voice/VoiceChecks.java`: `jsonListing` on `retrieve()`; `arrayListing` on `exchange`, still reading 256 bytes;
  the static `HttpClient` and the two catch ladders go.
- [ ] Bound: 1 MiB, the value `VoiceChecks.MAX_LISTING_BYTES` has, for clip answers and listings.

### The adapter interfaces

- [ ] `VoiceAdapter.transcribe` and `BatchTranscriptionAdapter.segments` lose their `HttpClient` parameter, in the
  five adapters. `VoiceTranscriptionService.HTTP` and `BatchTranscriptionService.HTTP` go.
  `SpeechSynthesisAdapter.speech` and `VoiceSynthesisService.HTTP` are unchanged.

### Tests and checks

- [ ] `SonioxAsyncTest`: the upload arrives as one `file` part with its file name and content type; both deletes are
  sent after a failure; a status other than 2xx is `VoiceException.providerUnavailable()`.
- [ ] `ElevenLabsVoiceTest` (parts asserted on the wire), `AzureSpeechTest`, `VoiceProviderClientTest`,
  `BatchTranscriptionTest`, `VoiceTranscriptionServiceTest` and the `VoiceAdapters` fixture. All already use a local
  server.
- [ ] Staging: a dictation clip with each Voice provider; the longest recording available with Soniox; a recording
  through OpenAI and through an OpenAI-compatible server; a connection check of each provider.
- [ ] `verification.md`: the measured bounds; lines removed and added.

## Pull request 3: Code Interpreter and the close-out

If the Code Interpreter stays as it is, this pull request holds documents only.

### Code Interpreter, whole or not at all

Needs the `exchange` case of `OutboundHttpTest` to hold.

- [ ] `shared/OutboundHttp.java`: `builder(Limits, ResponseErrorHandler)`, with its case in `OutboundHttpTest` (the
  handler reads a failed body within the bound).
- [ ] New `chat/interpreter/InterpreterApi.java`:

  ```java
  @HttpExchange
  interface InterpreterApi {
      @GetExchange("/health")
      Status health();
      @PostExchange(url = "/v1/files", contentType = "multipart/form-data")
      Uploaded upload(@RequestPart("file") HttpEntity<Resource> file);
      @PostExchange("/v1/execute")
      Result execute(@RequestBody Run run);
      @GetExchange("/v1/files/{id}")
      byte[] download(@PathVariable String id);
      @DeleteExchange("/v1/files/{id}")
      void delete(@PathVariable String id);
  }
  ```

- [ ] `chat/interpreter/InterpreterClient.java`: the Apache client, `request`, `send`, `Response`, `Handler` and both
  `requireSuccess` go. The handler reads at most 2 KiB of a failed body, throws `BusyException` on 429 and lets a
  404 on `DELETE` pass. A proxy is built per timeout. `executeStream` runs on `exchange` and keeps `consume` and
  `readLine`.
- [ ] One private method translates what `RestClient` throws back into today's contract: the `IOException` inside an
  `UncheckedIOException` or a `ResourceAccessException` is rethrown as it is, `ResponseTooLargeException` on a
  download becomes `TooLargeException`, and `health` turns a failed status into a `Health`. The public records and
  exceptions are unchanged, so `RunPythonTool`, `PresentationPreviewService` and `ChatInterpreterController` are
  untouched.
- [ ] `InterpreterClientTest` (already a local server) passes with its assertions unchanged; added: the upload is
  streamed, and a listener that throws makes the server see the connection close.
- [ ] Staging: Python run with a file in and a file out; a run stopped mid-output frees its slot.
- **Stays as it is if** the class does not get shorter, or closing does not cancel the run.

### Web, only if the Code Interpreter moved

Saves no lines; its only effect is that Apache HttpClient is left with one job, the page reader.

- [ ] `chat/web/WebCall.java`: `json` on a client from `OutboundHttp.builder(new Limits(15 s, 2 MiB))`.
- [ ] `chat/web/WebHttp.java`: `provider` and the `providers` client go; `page` and its DNS check stay.
- [ ] `WebProviderClientTest` stubs HTTP for provider calls; `WebHttpTest` keeps the page cases.
- [ ] Staging: a Web search with two providers; a page opened.

### Findings recorded, no code

- [ ] **MCP OAuth spike** (optional), on a branch that is not merged: Nimbus `oauth2-oidc-sdk` in `core`;
  `TokenRequest`, `TokenErrorResponse`, `ClientRegistrationRequest` and `AuthorizationServerMetadata` fed with the
  bytes `McpOAuthProtocol.send` reads, so the bound and the no-redirect rule stay ours. Recorded in the design:
  lines replaced; whether duplicate keys, trailing tokens and over-long fields are still refused; how
  `invalid_grant` maps; what `McpOAuthProtocolTest` says. Adopting it is an issue of its own.
- [ ] **`sources` assessment**: `RestSharePointGateway`, `RestGoogleDriveGateway`, `PaddleOcrVlClient` and
  `RestGoogleDriveAccountClient` (all under `sources/`) read in full and given one verdict each in the design, by
  the same order of choice. Known already: `BoundedDoclingClient` is choice 1; `RestGoogleDriveAccountClient` would
  gain close without draining, but hands its request factory to a `RestTemplate` for `NimbusJwtDecoder`. A follow-up
  issue if anything should move.

### Close-out

- [ ] `verification.md` complete; the specs and guidelines hold every durable fact.
- [ ] The increment moves to `completed/`, links to it are fixed and the roadmap is reconciled.

`clean check` runs in CI for each pull request.
