# Test audit

Baseline: `d45afa28eba4a3bcde99d1195d2a6bb5ee677129`. Inventory covers 66 Java `*Test.java` files (core 41, API 10, worker 9, connector 6), 11 frontend unit files and four browser specification files. Counts describe files, not independent Spring contexts or parameterized test invocations. Existing JUnit timings are a previous-run baseline, not fresh verification of this change.

## Concrete reductions

| Existing check | Decision and preserved evidence |
| --- | --- |
| `IdentityValueObjectsTest.actorIdCarriesOpaqueUuid` | Remove: asserts only the Java record-generated accessor. Keep all three owned constructor-validation cases; identity binding and persisted actor behavior remain separately covered |
| `ApiApplicationSmokeTest` | Remove its separate full context and duplicate discovery HTTP server. Move all three assertions to `BearerAuthenticationIntegrationTest`: real HTTP health, execution on a virtual thread, and absence of the normal springdoc handler. Keep the handler check independent of security so a protected-but-enabled route cannot hide the regression |

This removes one test method and one full application context, without removing the three runtime contracts. It does not establish that all other tests are minimal or that a particular startup duration was saved.

## Similar-looking tests that protect different contracts

| Suites | Why retained |
| --- | --- |
| `BusinessExceptionTest`, `ExtractionExceptionTest`, identity/request/property validation tests | Owned validation/classification and transport-credential boundaries, not generated getter behavior |
| `IamCapabilityTest` and `PostgresIamAuthorizationTest` | Pure implication expansion versus persisted grant union, fresh revocation and locking |
| `ApiExceptionHandlerTest` and session/source HTTP tests | Exhaustive safe failure-code mapping versus actual advice, serialization and framework HTTP errors |
| `SecurityConfigurationTest`, bearer and session integration | URI validation versus signed token cryptography/binding and cookie/PKCE/session lifecycle |
| IAM/JPA, source lifecycle and migration suites | Database constraints, historical upgrade data, transaction rollback, locking and concurrency are not implied by a successful current-schema happy path |
| Source API and worker file integration | Authorized durable HTTP commands versus real Redis delivery, worker processing and object cleanup |
| `DocumentSearchServiceTest`, `SearchIndexWorkIntegrationTest`, `OpenSearchRetrievalIntegrationTest` | Result eligibility/grouping, durable generation fencing/retries and actual OpenSearch index/query behavior |
| `ValidatedEmbeddingServiceTest`, `SpringAiEmbeddingHttpTest`, `OpenSearchGatewayTest` | Vector/result validation, actual SDK HTTP contract and sanitized provider failure behavior |
| Extraction parser tests and `DoclingServeIntegrationTest` | Deterministic mapping/failure cases versus live OCR and document parsing quality |
| Modulith/ArchUnit and generated OpenAPI/client/route checks | Explicit dependency and consumer-contract drift boundaries, justified exceptions to avoiding implementation/source-text assertions |
| Frontend component and fixture browser suites | Local interaction contracts versus routing, request/recovery and browser state. They do not prove deployed provider integration |

## Execution costs and gaps

- Nineteen Testcontainers annotations silently allowed Docker absence. They now use the mandatory default. The optional live Docling suite remains explicit and separately reported.
- All seven PostgreSQL fixture declarations now use the same pinned PostgreSQL 18.4 image as staging, replacing the previous 17.11 image. Migration tests still apply the production Flyway scripts.
- The worker file-processing test had an obsolete OTLP resource-attributes property. It now uses Spring Boot 4.1's `management.opentelemetry.resource-attributes.service.name`; the existing telemetry assertion verifies the actual emitted service identity.
- Six API `@SpringBootTest` files become five through the smoke consolidation. The remaining distinct contexts own bearer/session security, generated OpenAPI, source integration and telemetry configuration; merging their incompatible configuration would change their contract.
- Existing worker context eviction protects background scheduler/consumer shutdown. It is not replaced by shared mutable fixtures or blanket parallel execution.
- The previous local Vitest fork error was not reproduced: 52 tests passed in 25.34 s with default workers and in 19.69 s with two workers on this Windows host. The new worker cap is a bounded resource choice; neither run establishes the cause of the historical failure.
- Existing CI has no saved backend/unit failure reports and allows browser retry. This increment adds reports and makes required browser failures visible on the first run.
- Optional provider coverage, fixtures, local checks, CI and staging acceptance must be reported separately under [testing conventions](../../../conventions.md#testing).

See [research.md](research.md) for video timestamps behind the chosen boundaries and context-reuse approach, and `verification.md` for measured results once executed.
