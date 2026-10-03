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
