# Verification

## Pull request 1: the shared layer, Image, OIDC discovery and the model list

Run on 2026-10-03 on a development machine (Windows, Temurin 25.0.2), from `dca7609d` plus this change.

### Commands and results

| Command | Result |
| --- | --- |
| `gradlew compileJava compileTestJava` | All modules compile |
| `gradlew :core:test --tests 'io.memoryos.shared.OutboundHttpTest'` | 9 passed |
| `gradlew :core:test --tests 'io.memoryos.chat.image.*'` | 43 passed (`ImageHttpTest` 6, `ImageProviderClientTest` 15 unchanged, three more classes) |
| `gradlew :core:test --tests 'io.memoryos.iam.keycloak.OidcDiscoveryClientTest'` | 6 passed |
| `gradlew :core:test --tests 'io.memoryos.iam.identityprovider.DefaultIdentityProviderAdministrationTest'` | 9 passed |
| `gradlew :core:test --tests 'io.memoryos.ai.openai.*'` | 62 passed in 12 classes, among them `LocalModelMetadataTest` (3), `OpenAiProviderAdapterTest` (7) and `OpenAiReportedModelsTest` (5) |
| `gradlew :core:test --tests 'io.memoryos.ModulithArchitectureTest' --tests 'io.memoryos.CoreDependencyRulesTest'` | 1 and 2 passed |
| `gradlew :api:test --tests 'io.memoryos.api.chat.ChatSessionApiIntegrationTest.reportedModelsListsWhatTheProviderEndpointServes'` | Passed: the model list endpoint through the started application, PostgreSQL and Redis in containers |

`clean check` was not run on this machine: 1.3 GB of its 13.7 GB of memory was free, and the gate runs two 1.5 GB
test JVMs beside PostgreSQL and OpenSearch containers. It is left to CI.

### The stop condition

`OutboundHttpTest.aBodyThatNeverEndsStopsBeingReadAtTheBound`, measured once with timing added and then removed:
the call failed with `ResponseTooLargeException` 5 ms after it was sent, and the server's write failed 2 ms later,
with the deadline set to 60 s. `aResponseTheCallerStopsReadingIsClosedWithoutReadingTheRest` and
`aFailedAnswerIsReportedByStatusAndItsBodyIsNeverRead` end in about 10 ms each against the same never-ending body.

The cases were also run with the close-before-drain taken out of `BoundedRequestFactory` and Spring's own close
left in place: exactly those three failed at their 10 s limit, and the other six passed. The line was then restored.

### A probe that changed a test, not the code

The first version of `anUploadIsSentAsItIsProduced` failed. A probe showed the client streaming correctly (the body
ran on Spring's executor thread through `OutputStreamPublisher`, and the server received all 8192 bytes of the first
part within 7 ms). The JDK test server was the cause: `InputStream.readNBytes` ends with a zero-length read, which
makes its chunked request stream wait for the next chunk. The test reads with a loop instead.

### Lines

| | Removed | Added |
| --- | --- | --- |
| Callers (`ImageHttp`, `OidcDiscoveryClient`, `OpenAiProviderAdapter`, `LocalModelMetadata`) | 139 | 99 |
| Shared layer (`OutboundHttp`, `BoundedRequestFactory`) | 0 | 183 |
| Tests (`OutboundHttpTest`, `ImageHttpTest`, `OidcDiscoveryClientTest`, `LocalModelMetadataTest`) | 7 | 497 |

The callers lost 40 lines net; the shared layer is new code that the later pull requests reuse.

### A multipart body without a declared length

Spring writes a multipart body as it goes and declares no length, so over HTTP/1.1 it is sent chunked. The image
edit request declared its length before this change. A probe on 2026-10-03, with no credential, posted the same
20 KB multipart form to each provider over HTTP/1.1, once with `Content-Length` and once chunked:

| Endpoint | Declared | Chunked |
| --- | --- | --- |
| Soniox `/v1/files` | 401 | 401 |
| ElevenLabs `/v1/speech-to-text` | 422, `model_id` missing | 422, `model_id` missing |
| OpenAI `/v1/audio/transcriptions` | 401 | 401 |
| OpenAI `/v1/images/edits` | 401 | 401 |
| Cloudflare Workers AI `/ai/run/...` | 401 | 401 |

No endpoint refused the chunked form (no 411 or 400), and ElevenLabs read its fields from it. What the probe cannot
show is the answer to an authorized chunked upload; the staging checks cover that.

### Not yet verified

- `clean check` in CI.
- Staging: an image generated and edited with OpenAI and with Cloudflare; an identity provider added; a model list
  fetched from OpenRouter and from a local server.

## Pull request 2: Voice

Run on 2026-10-03 on the same machine, on top of pull request 1.

### Commands and results

| Command | Result |
| --- | --- |
| `gradlew compileJava compileTestJava` | All modules compile |
| `gradlew :core:test --tests 'io.memoryos.shared.OutboundHttpTest'` | 11 passed (two new: an `@HttpExchange` interface, JSON read whatever its content type) |
| `gradlew :core:test --tests 'io.memoryos.voice.*'` | All Voice classes pass, among them `SonioxAsyncTest` (5), `OpenAiAudioTest` (3, new), `ElevenLabsVoiceTest` (3), `AzureSpeechTest` (3), `VoiceProviderClientTest` (8), `VoiceTranscriptionServiceTest` (4), `BatchTranscriptionTest` (9) |
| The above with `io.memoryos.chat.image.*`, `OidcDiscoveryClientTest`, `io.memoryos.ai.openai.*`, `io.memoryos.meeting.*`, `ModulithArchitectureTest`, `CoreDependencyRulesTest` | 258 passed in 50 classes |

`clean check` is left to CI for the reason given above.

### The OpenAI SDK probe

A test class that was not kept sent a 64 MiB `InputStream` through `client.audio().transcriptions().create(...)`
of `openai-java` 4.49.0 to a local server, once for each answer shape.

| Question | Result |
| --- | --- |
| Is the upload streamed? | Yes: the server had read the first part while the source still held 63 MiB; the heap grew by 0 to 4 MiB a call |
| Does it declare its length? | No: `Transfer-Encoding: chunked` |
| File name and content type | `filename="meeting %22q%22.m4a"`, `Content-Type: audio/mp4`; the file part comes first |
| `{"text"}` | Read as a plain transcription |
| OpenAI's verbose answer, and one with a self-hosted server's extra fields | Read as verbose, segments with times |
| `{"text", "segments"}` without `duration` and `language` | Read as a **diarized** transcription; `asVerbose()` would fail |
| A segment without `avg_logprob` | Verbose, but reading the field throws |

The last two rows are why the recording upload reads its answer as a tree instead of through the SDK.

### Two things Spring is stricter about than the code it replaces

- `retrieve().body(JsonNode.class)` failed against the test servers, which declare no content type. The JSON
  converter of `OutboundHttp` now reads every content type; `OutboundHttpTest` covers an answer with none and one
  that says `text/plain`.
- Azure's `audio/wav; codecs=audio/pcm; samplerate=16000` is not a media type Spring parses. The body is written as
  bytes with its length; `AzureSpeechTest` still asserts the header text exactly.

### Lines

| | Removed | Added |
| --- | --- | --- |
| Voice callers and interfaces (15 files) and the new `SonioxApi` | 275 | 247 |
| `OutboundHttp` (`service`, the executor, the JSON converter) | 3 | 24 |

`SonioxAsync` went from 203 lines to 167, `VoiceChecks` from 69 to 51. No Voice file frames a multipart body by
hand any more, and three of the four static JDK clients are gone.

### Not yet verified

- `clean check` in CI.
- Staging: a dictation clip with each Voice provider; the longest recording available with Soniox, which also checks
  the 64 MiB transcript bound and the authorized upload without a declared length; a recording through OpenAI and
  through an OpenAI-compatible server; a connection check of each provider.

## Pull request 3: Code Interpreter and Web

Run on 2026-10-03 on the same machine, on top of pull request 2.

| Command | Result |
| --- | --- |
| `gradlew compileJava compileTestJava` | All modules compile |
| `gradlew :core:test --tests 'io.memoryos.chat.interpreter.*' --tests 'io.memoryos.chat.tools.*' --tests 'io.memoryos.chat.web.*'` | All pass; `InterpreterClientTest` with its assertions unchanged and one case added |

- **Stopping a run.** `aRunTheListenerStopsClosesTheConnectionSoTheServiceCanFreeItsSlot`: the service streams output
  for ever, the listener throws on the first chunk, and the server's write fails; 13 ms, with a ten-minute deadline.
- **Lines.** `InterpreterClient` 324 to 302, with `InterpreterApi` (36) new; `WebHttp` 120 to 142. Neither change
  makes the code shorter. They remove the Apache transport from the Code Interpreter and from provider calls.

### Not yet verified

- `clean check` in CI.
- Staging: Python run with a file in and a file out; a run stopped mid-output frees its slot; a Web search with two
  providers; a page opened.
