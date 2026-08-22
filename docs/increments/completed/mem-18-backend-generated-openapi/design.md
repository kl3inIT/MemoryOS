# MEM-18 design: backend-generated OpenAPI contract

## Outcome

`openapi.yml` becomes a deterministic committed snapshot of the live Spring MVC browser API. Spring controllers, request/response types, exception response metadata, and one API documentation configuration are the only source of API semantics; the Hey API client continues to be generated from the committed snapshot.

MEM-18 is stacked on MEM-16 because the generated contract must include its RFC 9457 `application/problem+json` responses. The eventual PR remains separate so the error-contract review does not absorb the generation infrastructure.

## Source of truth

Spring Boot 4.1-compatible springdoc 3.x inspects the real API application context. `springdoc-openapi-starter-webmvc-api` supplies the document endpoint without Swagger UI.

A single `GroupedOpenApi` includes `/api/**` only. Operation annotations own client-facing operation IDs, summaries, response status/media metadata, security requirements, and schema constraints that Spring cannot infer from executable types. The generated document must exclude browser redirects, OAuth callbacks, static pages, and actuator endpoints.

The root `openapi.yml` remains the committed frontend boundary and `web/openapi-ts.config.ts` continues to consume it. It is generated output, not a second editable contract.

## Runtime boundary

Springdoc API-doc endpoints are disabled in normal configuration. The contract test enables them through test properties and retrieves the grouped document with MockMvc from a full `@SpringBootTest` context. No Swagger UI dependency, production documentation endpoint, temporary application profile, or one-shot runtime mode is added.

## Deterministic snapshot

`OpenApiContractTest` retrieves the generated JSON document, verifies the exact public path set, and compares its parsed structure with the committed YAML snapshot. The document configuration supplies a stable same-origin server and stable API information; the test normalizes only generator fields proven to vary by environment.

Normal execution is read-only and fails with the exact refresh command when the snapshot differs. Setting `MEMORYOS_OPENAPI_WRITE=true` writes canonical YAML to `openapi.yml`. A second normal run must pass without a diff.

## CI and client generation

The existing GitHub workflow already runs both required gates on every pull request:

1. `./gradlew clean check --no-daemon` runs the live OpenAPI snapshot test and rejects backend/contract drift.
2. `pnpm --dir web check` regenerates the committed Hey API output and rejects client/contract drift.

MemoryOS does not need OrgMemory-style path filtering because every current PR runs both jobs. It does not need Kestra's bot branch and generated-SDK pull request because generated output belongs in the same small change that modifies its source.

## Published error model

Expected capability failures continue to use MEM-16 `ApiProblem` semantics: RFC 9457 fields plus optional capability `code`, with `application/problem+json` response media types. Framework-generated problems may omit `type` and `code`. Authentication failures that originate in Spring Security remain status-only where the runtime does not produce MVC Problem Details.

## Exclusions

No Swagger UI, production `/v3/api-docs`, external SDK publication, generated-code bot, Gradle task that boots production prerequisites, second OpenAPI document, or unrelated endpoint redesign is introduced.
