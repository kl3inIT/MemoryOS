# Audit evidence verification matrix

Contract: [Audit evidence](../specs/audit.md).

| Requirement | Durable verification |
| --- | --- |
| An event belongs to its change's transaction; a failed write leaves the change committed and is counted; details are limited to the declared fields; nothing rewrites or removes an event outside the retention sweep; a refusal survives its rollback; every action reads back from its value | `core/src/test/java/io/memoryos/audit/AuditTrailTest.java` |
| Pages are stable while events arrive; filters narrow the stream and stay inside the Tenant; `%` is searched literally; reading and exporting need `AUDIT_READ` and exporting is recorded; retention deletes only expired events | `core/src/test/java/io/memoryos/audit/AuditLogTest.java` |
| Deactivate and reactivate are recorded once each; a refused transition records nothing | `core/src/test/java/io/memoryos/iam/tenant/DefaultTenantMemberManagementTest.java` |
| Group changes are recorded; a scoped manager reaching another Group is recorded as denied, and a capability never held is not; a refusal raised under the Tenant lock does not deadlock | `core/src/test/java/io/memoryos/iam/group/PostgresGroupMembershipReplacementTest.java` |
| Invitation issue, rotate and revoke are recorded without the invitation secret; a rolled-back join leaves no event | `core/src/test/java/io/memoryos/iam/invitation/DefaultInvitationServiceTest.java` |
| Trusted admission is recorded once; a replay records nothing | `core/src/test/java/io/memoryos/iam/identity/DefaultTrustedIdentityAdmissionTest.java` |
| A refused sign-in is recorded with the asserted e-mail and reason | `api/src/test/java/io/memoryos/api/security/ActorSessionLoginSuccessHandlerTest.java` |
| Sign-in and sign-out are recorded over HTTP with endpoint and client address; an identity without membership is recorded as denied | `api/src/test/java/io/memoryos/api/security/SessionSecurityIntegrationTest.java` |
| A provider's creation records its data boundary, task model changes are recorded, and the provider key never appears | `api/src/test/java/io/memoryos/api/chat/ChatSessionApiIntegrationTest.java` — `taskModelFlowsAcceptOnlyTenantWideModelsAndProvidersRecordTheirDataBoundary` |
| The audit log is read and exported only with `AUDIT_READ`; the CSV guards formulas; the export is recorded; a malformed cursor is refused | `ChatSessionApiIntegrationTest.theAuditLogIsReadAndExportedOnlyWithAuditRead` |
| `audit` is a closed module with no capability dependency, and every capability that records into it declares it | `core/src/test/java/io/memoryos/ModulithArchitectureTest.java`; `core/src/test/java/io/memoryos/CoreDependencyRulesTest.java` keeps `audit.persistence` inside the module |
| `AUDIT_READ` is an editable, registered capability | `core/src/test/java/io/memoryos/iam/group/DefaultGroupServiceAuthorizationTest.java` |
| The Worker context starts with the audit writer and the retention task | `worker/src/test/java/io/memoryos/worker/ControlPlaneIntegrationTest.java` |
| The page lists readable actions, opens an event with only the changed fields, pages with the cursor and back, exports the filters on screen, offers a retry, and shows an unknown action by its code | `web/src/features/audit/audit-log-page.test.tsx` |
| HTTP paths and schemas are in the checked-in contract | `api/src/test/java/io/memoryos/api/OpenApiContractTest.java` |

The screens were reviewed at 1280 pixels in light and dark themes and at 390 pixels, with realistic Vietnamese
fixtures. No SIEM shipping is claimed.
