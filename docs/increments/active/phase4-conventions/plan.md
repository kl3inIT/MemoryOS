# Plan

- [x] API contract hygiene: headers, `contract/` records, typed `ApiProblem` extensions, springdoc customizer and `@CurrentActor`.
- [x] Typed Chat failure codes.
- [x] Identity people-and-Group search; Google Drive account client and sign-in admission out of `api`; branch and meeting-publish API tests.
- [ ] Imports instead of inline qualified names; fluent logging everywhere.
- [ ] `deploy.sh` ERR trap; delivered increments to `completed/`; roadmap reconciled.

## Verification

- API contract hygiene: `OpenApiContractTest` (with and without `MEMORYOS_OPENAPI_WRITE`), `SecurityResponseHeadersTest`, `SessionSecurityIntegrationTest`, `SourceApiIntegrationTest`, `SharePointSourceApiTest`, `SharePointCredentialApiTest`, `GoogleDriveOAuthTest`, `McpOAuthCallbackTest`, `DocumentOriginalResponsesTest`, `ChatEventStreamTest` and the library, meeting, feedback and file cases of `ChatSessionApiIntegrationTest`; web `check:api`, `typecheck`, `lint`, `format:check`, `test:unit` (714 tests).
- Ownership follow-ups: `GET /api/identity/principals` (IAM `PrincipalSearch`, `CHAT_WRITE` as the removed `/api/chat/persona-share-options` required) with `openapi.yml` and the Hey API client regenerated; the Google account OAuth protocol moved to the provider bundle behind the connector port `GoogleDriveAccountClient`, its endpoints bound once in `GoogleDriveProviderProperties`; sign-in admission decided by IAM `SignInAdmission` in one transaction. Compiled `core`, `sources`, `api`, `worker` with tests; `:core:test` `ModulithArchitectureTest`, `CoreDependencyRulesTest`, `io.memoryos.iam.*` (121, including `DefaultSignInAdmissionTest`); `:sources:test` `SourcesDependencyRulesTest` and `*GoogleDrive*` (45, including `RestGoogleDriveAccountClientTest`); `:api:test` `OpenApiContractTest` (with and without `MEMORYOS_OPENAPI_WRITE`), `SessionSecurityIntegrationTest` (16), `ActorSessionLoginSuccessHandlerTest` (4), `GoogleDriveOAuthTest` (7), `SourceApiIntegrationTest` (29), `SharePointSourceApiTest`, `SharePointCredentialApiTest` and the principal-search, branch and meeting-publish cases of `ChatSessionApiIntegrationTest`; `:worker:test` `ControlPlaneIntegrationTest`; web `check:api`, `check:i18n`, `typecheck`, `lint`, `format:check`, `test:unit` (716 tests).
