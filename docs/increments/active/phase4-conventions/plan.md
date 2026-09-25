# Plan

- [x] API contract hygiene: headers, `contract/` records, typed `ApiProblem` extensions, springdoc customizer and `@CurrentActor`.
- [x] Typed Chat failure codes.
- [ ] Identity people-and-Group search; Google Drive account client and sign-in admission out of `api`; branch and meeting-publish API tests.
- [ ] Imports instead of inline qualified names; fluent logging everywhere.
- [ ] `deploy.sh` ERR trap; delivered increments to `completed/`; roadmap reconciled.

## Verification

- API contract hygiene: `OpenApiContractTest` (with and without `MEMORYOS_OPENAPI_WRITE`), `SecurityResponseHeadersTest`, `SessionSecurityIntegrationTest`, `SourceApiIntegrationTest`, `SharePointSourceApiTest`, `SharePointCredentialApiTest`, `GoogleDriveOAuthTest`, `McpOAuthCallbackTest`, `DocumentOriginalResponsesTest`, `ChatEventStreamTest` and the library, meeting, feedback and file cases of `ChatSessionApiIntegrationTest`; web `check:api`, `typecheck`, `lint`, `format:check`, `test:unit` (714 tests).
