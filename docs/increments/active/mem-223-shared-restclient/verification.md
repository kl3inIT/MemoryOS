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

`clean check` was not run on this machine; it is left to CI.

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

### Not yet verified

- `clean check` in CI.
- Staging: an image generated and edited with OpenAI and with Cloudflare; an identity provider added; a model list
  fetched from OpenRouter and from a local server.
