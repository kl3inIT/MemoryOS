# MEM-105 Source access modes — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace PUBLIC/RESTRICTED with Public, Private and Auto Sync Source access, and enforce the retained Google Drive permissions in Search, Chat document search, citations and original-PDF reads.

**Architecture:** V56 renames RESTRICTED to PRIVATE and adds SYNC. One SQL rule in `JdbcSourceDocumentRepository` derives Auto Sync grant tokens from the last successful MEM-88 snapshot. The same rule is used for index-time `DocumentAccess` and for the post-query `READ_SCOPE` recheck. Reader tokens come from the verified login email in `actor_profiles`. `GoogleDriveAclChanged` and mode changes enqueue the existing in-place ACCESS refresh.

**Tech stack:** Spring Boot, `JdbcClient`, Flyway and PostgreSQL (JSONB), OpenSearch, React/TanStack and hey-api.

**Design:** [design.md](design.md). **Linear:** [MEM-105](https://linear.app/memory-os/issue/MEM-105).

- [x] Create branch `nhuxuanviet/mem-105-source-access-modes` from the MEM-88 head and move MEM-105 to In Progress.
- [x] Agree the design with the user.

## File map

| File | Responsibility |
| --- | --- |
| `core/src/main/resources/db/migration/V56__source_access_modes.sql` | Rename RESTRICTED to PRIVATE, allow SYNC, and store the requested access on Drive creation intents |
| `core/.../connector/SourceAccess.java` | `PUBLIC, PRIVATE, SYNC` |
| `core/.../connector/application/SourceAccessPolicy.java` | Defaults and authority per Source type |
| `core/.../connector/application/DefaultSourceManagementService.java` | FILE creation passes its type |
| `core/.../connector/GoogleDriveSourceService.java`, `application/DefaultGoogleDriveSourceService.java` | Drive creation carries access through the intent |
| `core/.../connector/persistence/JdbcGoogleDriveSelectionRepository.java` | `access_type` on the selection operation and `Intent` |
| `core/.../connector/persistence/JdbcGoogleDriveSourceRepository.java` | Insert the pair with the resolved access |
| `core/.../connector/persistence/JdbcGoogleDriveCredentialRepository.java` | Drop the RESTRICTED discriminator |
| `core/.../connector/persistence/JdbcSourceRepository.java` | `updateAccess` for FILE and Drive; FILE rejects SYNC |
| `core/.../connector/persistence/JdbcSourceDocumentRepository.java` | `SYNC_GRANTS`, `READER_TOKENS`, document/source read scopes, `documentAccess`, `actorAccessTokens` |
| `core/.../ingestion/persistence/JdbcSearchWorkRepository.java` | `enqueueDocumentAccess` |
| `core/.../ingestion/application/SearchProjectionMaintenance.java` | Listen to `GoogleDriveAclChanged` |
| `api/.../source/contract/CreateGoogleDriveSourceRequest.java`, `GoogleDriveSourceController.java` | Optional `access` |
| `web/src/features/sources/*` | Mode choice, Change visibility, filter, badge; translations |
| `docs/decisions/0011-verified-email-source-permission-matching.md` | ADR |
| Specs, test matrices, ARCHITECTURE, README, roadmap | Current contract |

---

### Task 1: V56 and the three-value enum

**Files:**
- Create: `core/src/main/resources/db/migration/V56__source_access_modes.sql`
- Modify: `core/src/main/java/io/memoryos/connector/SourceAccess.java`
- Test: `core/src/test/java/io/memoryos/connector/persistence/SourceAccessModesMigrationTest.java`

- [x] **Step 1: Write the failing migration test**

```java
package io.memoryos.connector.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.memoryos.TestDatabase;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class SourceAccessModesMigrationTest {
    @Test
    void renamesRestrictedToPrivateAndAcceptsOnlyTheThreeModes() throws Exception {
        try (var database = TestDatabase.freshPostgres("55")) {
            var jdbc = JdbcClient.create(database);
            UUID tenant = UUID.randomUUID(), restricted = UUID.randomUUID(), open = UUID.randomUUID();
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'modes','Modes','ACTIVE','TEST')")
                    .param("id", tenant).update();
            pair(jdbc, tenant, restricted, "RESTRICTED");
            pair(jdbc, tenant, open, "PUBLIC");

            Flyway.configure().dataSource(database).locations("classpath:db/migration").load().migrate();

            assertThat(access(jdbc, restricted)).isEqualTo("PRIVATE");
            assertThat(access(jdbc, open)).isEqualTo("PUBLIC");
            jdbc.sql("UPDATE connector_credential_pairs SET access_type='SYNC' WHERE id=:id").param("id", restricted).update();
            assertThat(access(jdbc, restricted)).isEqualTo("SYNC");
            assertThatThrownBy(() -> jdbc.sql("UPDATE connector_credential_pairs SET access_type='RESTRICTED' WHERE id=:id")
                    .param("id", open).update()).hasMessageContaining("ck_pairs_access");
            assertThat(jdbc.sql("""
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_name='google_drive_selection_operations' AND column_name='access_type'
                    """).query(String.class).single()).isEqualTo("YES");
        }
    }

    private static void pair(JdbcClient jdbc, UUID tenant, UUID id, String access) {
        jdbc.sql("INSERT INTO credentials(id,tenant_id,name,credential_kind,status) VALUES(:id,:tenant,'Test','NO_AUTH','ACTIVE')")
                .param("id", id).param("tenant", tenant).update();
        jdbc.sql("INSERT INTO connectors(id,tenant_id,name,connector_type,status) VALUES(:id,:tenant,'Test','FILE','ACTIVE')")
                .param("id", id).param("tenant", tenant).update();
        jdbc.sql("""
                INSERT INTO connector_credential_pairs(id,tenant_id,connector_id,credential_id,access_type,status)
                VALUES(:id,:tenant,:id,:id,:access,'ACTIVE')
                """).param("id", id).param("tenant", tenant).param("access", access).update();
    }

    private static String access(JdbcClient jdbc, UUID id) {
        return jdbc.sql("SELECT access_type FROM connector_credential_pairs WHERE id=:id").param("id", id).query(String.class).single();
    }
}
```

- [x] **Step 2: Run it and confirm it fails.** Run `.\gradlew.bat :core:test --tests '*SourceAccessModesMigrationTest'`. Expected: FAIL, with RESTRICTED still stored.

- [x] **Step 3: Write the migration**

```sql
ALTER TABLE connector_credential_pairs DROP CONSTRAINT ck_pairs_access;
UPDATE connector_credential_pairs SET access_type = 'PRIVATE' WHERE access_type = 'RESTRICTED';
ALTER TABLE connector_credential_pairs
    ADD CONSTRAINT ck_pairs_access CHECK (access_type IN ('PUBLIC', 'PRIVATE', 'SYNC'));

ALTER TABLE google_drive_selection_operations
    ADD COLUMN access_type VARCHAR(16),
    ADD CONSTRAINT ck_google_selection_access CHECK (access_type IN ('PUBLIC', 'PRIVATE', 'SYNC'));
```

- [x] **Step 4: Rename the enum**

```java
public enum SourceAccess {
    /** Every active Tenant member. */
    PUBLIC,
    /** Members of the Groups associated with the Source. */
    PRIVATE,
    /** Readers granted by the provider's retained per-file permissions; Google Drive only. */
    SYNC
}
```

Replace `SourceAccess.RESTRICTED` with `SourceAccess.PRIVATE` in main code. The Drive-specific hard-codes are handled in Task 2. Change the SQL literal `'RESTRICTED'` to `'PRIVATE'` in `JdbcSourceDocumentRepository.SEARCHABLE_SOURCE` for now; Task 3 replaces it.

- [x] **Step 5: Update tests that run on the latest schema.** Replace `'RESTRICTED'` and `SourceAccess.RESTRICTED` with `PRIVATE` in:
  - `PostgresSourceRunHistoryTest`
  - `PostgresSourceLifecycleTest`
  - `PostgresGoogleDriveSyncTest`
  - `PostgresGoogleDriveAclRepositoryTest`
  - `SourceOriginalPdfQueryTest`
  - `SearchAuthorizationCostMeasurementTest`
  - `SearchIndexWorkIntegrationTest`
  - `SourceApiIntegrationTest`

  Keep `RESTRICTED` where a test seeds a pre-V56 schema (`GroupMigrationSeedTest`, `GoogleDriveOAuthClientMigrationTest`, and the first test of `SourceSearchMetadataMigrationTest`). In `SourceSearchMetadataMigrationTest`, give `seed(...)` a `String driveAccess` parameter: the V34 test passes `"RESTRICTED"` and the others pass `"PRIVATE"`. In its first test, migrate to the latest version after asserting the V35 backfill and before using the repository, because the Task 3 SQL reads V54 tables.

- [x] **Step 6: Run.** `.\gradlew.bat :core:compileTestJava :core:test --tests '*SourceAccessModesMigrationTest' --tests '*SourceSearchMetadataMigrationTest' --tests '*PostgresSourceLifecycleTest' --tests '*GroupMigrationSeedTest' --tests '*GoogleDriveOAuthClientMigrationTest'`. Expected: PASS.

- [x] **Step 7: Commit** as `feat(connector): rename RESTRICTED Source access to PRIVATE and add SYNC (MEM-105)`.

### Task 2: Access per Source type, Drive creation and mode changes

**Files:** `SourceAccessPolicy`, `DefaultSourceManagementService`, `GoogleDriveSourceService`, `DefaultGoogleDriveSourceService`, `JdbcGoogleDriveSelectionRepository`, `JdbcGoogleDriveSourceRepository`, `JdbcGoogleDriveCredentialRepository`, `JdbcSourceRepository`, `CreateGoogleDriveSourceRequest`, `CreateFileSourceRequest`, `GoogleDriveSourceController`. Tests: `GoogleDriveCredentialAuthorityTest`, `GoogleDriveSelectionOperationTest`, `SourceApiIntegrationTest`.

- [x] **Step 1: Write failing tests.**
  - In `GoogleDriveCredentialAuthorityTest`, add:
    - `createdDriveSourceDefaultsToAutoSyncAndKeepsTheRequestedMode`: create and process a SPECIFIC source with `null` access, then assert `access_type='SYNC'`; do the same with `PRIVATE` and assert `PRIVATE`.
    - `scopedManagerCannotCreatePublicDriveSource`: expect `SourceException` for `SourceAccess.PUBLIC` from a scoped manager.
  - In `SourceApiIntegrationTest`:
    - `POST /api/sources/file` with `"access":"SYNC"` returns 400.
    - `PUT /api/sources/{drive}/access` with `SYNC`, `PUBLIC` and `PRIVATE` succeeds for a global manager.
    - `PUT` of `SYNC` on a FILE source returns 409.
- [x] **Step 2: Run them and confirm they fail** (compile failure on the new `create` argument, then assertion failures).
- [x] **Step 3: Make `SourceAccessPolicy` type-aware.**

```java
public Creation creation(ActorId actorId, SourceType type, @Nullable SourceAccess requestedAccess, Collection<GroupId> groupIds) {
    return resolve(authorization.require(actorId, IamCapability.SOURCES_MANAGE, true), actorId, type, requestedAccess, groupIds);
}

@Transactional(propagation = Propagation.MANDATORY)
public Creation lockCreation(ActorId actorId, SourceType type, @Nullable SourceAccess requestedAccess, Collection<GroupId> groupIds) {
    return resolve(authorization.lockAndRequireScopedMutation(actorId, IamCapability.SOURCES_MANAGE), actorId, type,
            requestedAccess, groupIds);
}

/** FILE defaults to PUBLIC (global) or PRIVATE (scoped); Google Drive defaults to SYNC. SYNC needs provider ACLs. */
static SourceAccess access(SourceType type, boolean global, @Nullable SourceAccess requested) {
    SourceAccess access = requested != null ? requested
            : type == SourceType.GOOGLE_DRIVE ? SourceAccess.SYNC : global ? SourceAccess.PUBLIC : SourceAccess.PRIVATE;
    if (access == SourceAccess.SYNC && type != SourceType.GOOGLE_DRIVE)
        throw SourceException.invalid("Auto Sync requires a Google Drive source.", "sync access without provider permissions");
    if (!global && access == SourceAccess.PUBLIC)
        throw SourceException.invalid("Managed sources must be private or use Auto Sync.", "scoped source publication denied");
    return access;
}
```

  `resolve` calls `access(type, global, requestedAccess)` and keeps the existing group validation. `DefaultSourceManagementService.createFileSource` passes `SourceType.FILE`.
- [x] **Step 4: Carry access through Drive creation.**
  - `GoogleDriveSourceService.create(..., List<GroupId> groupIds, @Nullable SourceAccess access)`.
  - `DefaultGoogleDriveSourceService.create`:
    - resolve `sourceAccess.creation(actor, SourceType.GOOGLE_DRIVE, access, groupIds)` and `lockCreation(...)` the same way;
    - add `creation.access().name()` to the `requestHash` list after the group IDs;
    - pass `creation.access()` to `selections.submit(...)`.
  - `JdbcGoogleDriveSelectionRepository.submit(..., List<GroupId> groupIds, SourceAccess access)` inserts `access_type` with `.param("access", access.name())`.
  - `Intent` gains `@Nullable SourceAccess access`, read as `r.getString("access_type") == null ? null : SourceAccess.valueOf(r.getString("access_type"))`.
  - `requireIntent` and `activate` call `sourceAccess.lockCreation/creation(intent.actorId(), SourceType.GOOGLE_DRIVE, intentAccess(intent), intent.groupIds())`, where `intentAccess` returns `PRIVATE` for a legacy `null` (the pre-V56 behaviour).
  - `activate` passes the resolved access to `drive.create(..., roots, access)`.
  - `JdbcGoogleDriveSourceRepository.create(..., List<Root> roots, SourceAccess access)` binds `:access` instead of `'RESTRICTED'`.
  - `JdbcGoogleDriveCredentialRepository.credentialId` drops `AND p.access_type = 'RESTRICTED'`; the Drive connector-type predicate already identifies the Source.
- [x] **Step 5: Open `updateAccess` to Drive.**

```java
public void updateAccess(TenantId tenantId, SourceId sourceId, SourceAccess access) {
    int updated = jdbcClient.sql("""
            UPDATE connector_credential_pairs pair
            SET access_type = :access, updated_at = CURRENT_TIMESTAMP
            WHERE pair.tenant_id = :tenantId AND pair.id = :pairId
              AND EXISTS (SELECT 1 FROM connectors connector
                WHERE connector.tenant_id = pair.tenant_id AND connector.id = pair.connector_id
                  AND (connector.connector_type = 'GOOGLE_DRIVE'
                    OR (connector.connector_type = 'FILE' AND :access <> 'SYNC')))
            """).param("tenantId", tenantId.value()).param("pairId", sourceId.value())
            .param("access", access.name()).update();
    if (updated != 1) throw SourceException.conflict("Auto Sync requires a Google Drive source");
    events.publishEvent(new io.memoryos.connector.SourceAccessChanged(tenantId, sourceId));
}
```

  Change the `@Operation` summary of `updateSourceAccess` to "Update Source access". If the MEM-94 `publish` permission is computed only for FILE, extend it to Google Drive with the same exclusive global rule.
- [x] **Step 6: API.**
  - `CreateGoogleDriveSourceRequest` adds `@Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "PUBLIC, PRIVATE or SYNC; defaults to SYNC.") @Nullable SourceAccess access`.
  - The controller passes `body.access()`.
  - `CreateFileSourceRequest.access` gains the description "PUBLIC or PRIVATE; SYNC is rejected."
  - Update the remaining `create(` call sites in tests with a trailing `null`.
- [x] **Step 7: Run.** `.\gradlew.bat :core:test --tests '*GoogleDriveCredentialAuthorityTest' --tests '*GoogleDriveSelectionOperationTest' --tests '*PostgresSourceLifecycleTest' :api:test --tests '*SourceApiIntegrationTest'`. Expected: PASS.
- [x] **Step 8: Commit** as `feat(connector): choose Source access per type and at Drive creation (MEM-105)`.

### Task 3: Auto Sync enforcement

**Files:**
- Modify: `core/src/main/java/io/memoryos/connector/persistence/JdbcSourceDocumentRepository.java`
- Test: `core/src/test/java/io/memoryos/connector/persistence/SourceSyncAccessTest.java`
- Modify test: `SourceSearchMetadataMigrationTest`, where Drive PUBLIC is now readable.

- [x] **Step 1: Write the failing test.**
  - **Fixture.**
    - A tenant; members `owner` (verified email `owner@example.test`), `domainMate` (verified `mate@example.test`), `outsider` (verified `outsider@other.test`), `unverified` (email `owner@example.test` with `email_verified=false`), and `groupMember`.
    - Each member gets an `external_identity_bindings` row plus an `actor_profiles` row.
    - A Google Drive pair with `access_type='SYNC'` and a `google_drive_sources` row.
    - One document per case, each mapped through a `connector_items` row whose `provider_file_id` names the file.
    - A Group associated with the Source that contains `groupMember`.
  - **Snapshots** are inserted with `permissions_json` serialized by `new tools.jackson.databind.ObjectMapper().writeValueAsString(List<GoogleDriveProvider.Permission>)`, the repository's own format.
  - **Cases, via `readableDocuments` and `documentAccess`:**
    - A user permission for `OWNER@example.test` → owner reads; token `google_user:owner@example.test`; the unverified actor, the outsider and the Group member do not read.
    - A discoverable domain `example.test` → owner and domainMate read; token `google_domain:example.test`.
    - A domain `example.test` with `allowFileDiscovery=false` → nobody reads; no token.
    - `anyone` with `allowFileDiscovery=false` → everyone reads; `DocumentAccess.everyone()` is true.
    - A group permission → nobody reads.
    - A user permission with `deleted=true`, or with an `expirationTime` in the past → nobody reads.
    - No snapshot row, or a failed-only row (`observation_revision=0`) → nobody reads.
    - After the snapshot is followed by a failed attempt (status `FAILED`, permissions retained) → still readable.
    - Bumping `google_drive_sources.revision` and `google_drive_credentials.credential_revision` → still readable.
    - The same document mapped from a PUBLIC FILE source → everyone reads; the PUBLIC origin sets `everyone`.
    - Switching the pair to PRIVATE → only `groupMember` reads; to PUBLIC → everyone.
    - `actorAccessTokens` returns `google_user:…` and `google_domain:…` only for verified profiles, and nothing for an inactive membership.
    - `searchableSources` includes the SYNC Source for every active member.
    - Parity: for every case, a reader is in `readableDocuments` exactly when `documentAccess.everyone()` holds or its tokens intersect `actorAccessTokens(reader)`.
- [x] **Step 2: Run it and confirm it fails.** `.\gradlew.bat :core:test --tests '*SourceSyncAccessTest'`. Expected: FAIL.
- [x] **Step 3: Implement the single rule.**

```java
private static final String SEARCHABLE_SOURCE = "c.connector_type IN ('FILE','GOOGLE_DRIVE')";
/** Token of a provider grant that admits every active Tenant member; stored as {@code access_public}. */
private static final String PUBLIC_GRANT = "everyone";
/**
 * Grant tokens of a SYNC mapping {@code m} of pair {@code p}, from the retained successful Google Drive snapshot of
 * its item, interpreted as in Onyx (MEM-105 design). The index and the post-query recheck both use this rule.
 */
private static final String SYNC_GRANTS = """
        SELECT CASE entry.permission->>'type'
                 WHEN 'user' THEN 'google_user:' || LOWER(entry.permission->>'emailAddress')
                 WHEN 'domain' THEN 'google_domain:' || LOWER(entry.permission->>'domain')
                 ELSE 'everyone' END AS token
        FROM connector_items sync_item
        JOIN google_drive_acl_snapshots snapshot ON snapshot.tenant_id=sync_item.tenant_id AND snapshot.source_id=p.id
            AND snapshot.file_id=sync_item.provider_file_id AND snapshot.observation_revision>0
        CROSS JOIN LATERAL jsonb_array_elements(snapshot.permissions_json) AS entry(permission)
        WHERE sync_item.tenant_id=m.tenant_id AND sync_item.id=m.connector_item_id
            AND COALESCE(entry.permission->>'deleted','false')<>'true'
            AND (entry.permission->>'expirationTime' IS NULL
                OR CAST(entry.permission->>'expirationTime' AS TIMESTAMPTZ)>statement_timestamp())
            AND ((entry.permission->>'type'='user' AND BTRIM(COALESCE(entry.permission->>'emailAddress',''))<>'')
                OR (entry.permission->>'type'='domain' AND BTRIM(COALESCE(entry.permission->>'domain',''))<>''
                    AND COALESCE(entry.permission->>'allowFileDiscovery','true')<>'false')
                OR entry.permission->>'type'='anyone')
        """;
/** A reader's provider identities: the verified login email and its domain. Unverified observations grant nothing. */
private static final String READER_TOKENS = """
        SELECT 'google_user:' || LOWER(profile.email) AS token FROM actor_profiles profile
        WHERE profile.actor_id=:actor AND profile.email_verified AND profile.email ~ '^[^@[:space:]]+@[^@[:space:]]+$'
        UNION ALL
        SELECT 'google_domain:' || LOWER(SPLIT_PART(profile.email,'@',2)) FROM actor_profiles profile
        WHERE profile.actor_id=:actor AND profile.email_verified AND profile.email ~ '^[^@[:space:]]+@[^@[:space:]]+$'
        """;
private static final String READ_SCOPE = """
        EXISTS (
            SELECT 1 FROM tenant_memberships reader
            JOIN tenants tenant ON tenant.id=reader.tenant_id AND tenant.status='ACTIVE'
            WHERE reader.tenant_id=p.tenant_id AND reader.actor_id=:actor AND reader.status='ACTIVE'
        )
        AND (p.access_type='PUBLIC'
            OR (p.access_type='PRIVATE' AND EXISTS (
                SELECT 1 FROM source_group_grants grant_row
                JOIN iam_group_memberships member ON member.tenant_id=grant_row.tenant_id
                    AND member.group_id=grant_row.group_id AND member.actor_id=:actor
                WHERE grant_row.tenant_id=p.tenant_id AND grant_row.connector_credential_pair_id=p.id))
            OR (p.access_type='SYNC' AND %s))
        """;
/** Document reads recheck the mapped file's provider grants. */
private static final String DOCUMENT_READ_SCOPE = READ_SCOPE.formatted("EXISTS (SELECT 1 FROM (" + SYNC_GRANTS
        + ") sync_grant WHERE sync_grant.token='everyone' OR sync_grant.token IN (" + READER_TOKENS + "))");
/** Source lists expose SYNC Sources to active members; each document is still rechecked. */
private static final String SOURCE_READ_SCOPE = READ_SCOPE.formatted("TRUE");
```

  - `readableDocuments`, `sourceMetadata` and `originalPdf` use `DOCUMENT_READ_SCOPE`. `searchableSources` and `searchableSourceOptions` use `SOURCE_READ_SCOPE`.
  - `documentAccess` unions two branches:
    - non-SYNC mappings, which LEFT JOIN `source_group_grants` for PRIVATE;
    - SYNC mappings, via `CROSS JOIN LATERAL (SYNC_GRANTS) sync_grant`.

    It selects `(access_type, group_id, token)` with `CAST(NULL AS UUID)` and `CAST(NULL AS TEXT)`.
  - Java computes `everyone = PUBLIC row || token equals PUBLIC_GRANT`, and `tokens = group tokens of PRIVATE rows ∪ non-public SYNC tokens`.
  - `actorAccessTokens` adds `SELECT reader_token.token FROM (READER_TOKENS) reader_token WHERE EXISTS (active membership of :tenant/:actor)`.
  - Update the `DocumentAccess` Javadoc: PUBLIC, PRIVATE Group tokens, SYNC provider tokens.
- [x] **Step 4: Update `SourceSearchMetadataMigrationTest`.** The block starting "Drive never inherits FILE public access" now asserts that a PUBLIC Drive Source is readable by member and outsider, is present in scope, and returns its metadata. The following `UPDATE` uses `'PRIVATE'`.
- [x] **Step 5: Run.** `.\gradlew.bat :core:test --tests '*SourceSyncAccessTest' --tests '*SourceSearchMetadataMigrationTest' --tests '*SourceOriginalPdfQueryTest' --tests '*SearchAuthorizationCostMeasurementTest'`. Expected: PASS.
- [x] **Step 6: Commit** as `feat(connector): enforce Auto Sync Google Drive permissions in reads and index access (MEM-105)`.

### Task 4: ACCESS refresh on ACL change

**Files:** `JdbcSearchWorkRepository`, `SearchProjectionMaintenance`. Test: extend the existing ACCESS coverage in `SearchIndexWorkIntegrationTest`, or add `DocumentAccessRefreshTest` next to it if that class needs OpenSearch.

- [x] **Step 1: Write the failing test.** Publish `GoogleDriveAclChanged(tenant, syncSource, file, List.of(document), 2, SUCCEEDED, null)` to `SearchProjectionMaintenance.aclChanged` and assert that one `search_index_operations` row with action `ACCESS` and status `NOT_STARTED` exists for the document's searchable generation. Assert that the same event for a PRIVATE source enqueues nothing.
- [x] **Step 2: Implement.**

```java
/** Queues an access refresh for the listed searchable documents of a SYNC Source, resetting a pending refresh. */
@Transactional
public void enqueueDocumentAccess(TenantId tenant, SourceId source, List<DocumentId> documents, String identity) {
    if (documents.isEmpty()) return;
    jdbc.sql("""
            INSERT INTO search_index_operations(id,tenant_id,document_id,generation,action,index_identity)
            SELECT gen_random_uuid(),d.tenant_id,d.id,d.searchable_generation,'ACCESS',:identity FROM documents d
            WHERE d.tenant_id=:tenant AND d.id IN (:documents) AND d.status='ELIGIBLE'
                AND d.searchable_generation IS NOT NULL AND d.search_index_identity=:identity
                AND EXISTS (SELECT 1 FROM documents_by_connector_credential_pair m
                    JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.connector_credential_pair_id
                    WHERE m.tenant_id=d.tenant_id AND m.document_id=d.id AND p.id=:source AND p.access_type='SYNC')
            ON CONFLICT (tenant_id,document_id,generation,action,index_identity) DO UPDATE
            SET status='NOT_STARTED',processing_attempts=0,error_code=NULL,completed_at=NULL,claim_token=NULL,
                lease_expires_at=NULL,next_dispatch_at=CURRENT_TIMESTAMP,dispatch_token=NULL,dispatch_lease_expires_at=NULL
            """).param("tenant", tenant.value()).param("source", source.value())
            .param("documents", documents.stream().map(DocumentId::value).toList()).param("identity", identity).update();
}
```

```java
/** Runs in the snapshot transaction; only SYNC Sources derive index access from provider permissions. */
@EventListener
public void aclChanged(GoogleDriveAclChanged event) {
    work.enqueueDocumentAccess(event.tenantId(), event.sourceId(), event.documentIds(), index.identity());
}
```

- [x] **Step 3: Run** the test class and `PostgresGoogleDriveSyncTest`. Expected: PASS.
- [x] **Step 4: Commit** as `feat(ingestion): refresh index access when Auto Sync permissions change (MEM-105)`.

### Task 5: Contract and web

**Files:** `openapi.yml`, `web/src/lib/hey-api/*`, `create-file-source-page.tsx`, `create-google-drive-source-page.tsx`, `source-detail-page.tsx`, `sources-page.tsx`, `source-status-badge.tsx`, `web/src/i18n/app-translations.ts`, and the web tests with `RESTRICTED` fixtures.

- [x] **Step 1: Regenerate.** Run `$env:MEMORYOS_OPENAPI_WRITE='true'; .\gradlew.bat :api:test --tests '*OpenApiContractTest'`, then `pnpm generate:api` in `web`.
- [x] **Step 2: FILE creation.** Use state `"PUBLIC" | "PRIVATE"`, and send `scoped ? "PRIVATE" : access`. The option value becomes `PRIVATE`.
- [x] **Step 3: Drive creation.**
  - Add `const [access, setAccess] = useState<SourceAccess>("SYNC")`, where `SourceAccess = SourceSummary["access"]`, and include `access: effectiveAccess` in `proposal`. `effectiveAccess` is `access` unless a scoped manager holds `PUBLIC`, in which case it is `SYNC`.
  - Add a labelled `Select` before the Group picker with these options:
    - Auto Sync: "only people who can open the file in Google Drive";
    - Private: "selected group members";
    - Public: "everyone in this Tenant" (global managers only).
  - Replace the "Private Source … Google per-file permissions are not synchronized." paragraph with mode-specific help. For SYNC: "Readers need access to each file in Google Drive and a verified login email; groups decide who manages this Source." For PRIVATE and PUBLIC, reuse the FILE wording.
- [x] **Step 4: Source detail.**
  - `canManageAccess = can(source, "publish")` for FILE and Drive.
  - The state type is `SourceSummary["access"]`.
  - The options are `PUBLIC` and `PRIVATE`, plus `SYNC` for `GOOGLE_DRIVE`.
  - The help text describes all three modes.
- [x] **Step 5: Sources list and badge.**
  - The filter offers `PUBLIC` "Workspace members", `PRIVATE` "Private" and `SYNC` "Auto Sync".
  - `SourceAccessBadge` maps PUBLIC to `Users` with "Workspace members", PRIVATE to `Lock` with "Private", and SYNC to `RefreshCw` with "Auto Sync" and the title "Readers need access to the file in Google Drive."
- [x] **Step 6: Translations and tests.**
  - Add the Vietnamese entries and remove keys that are no longer used (`Restricted`, `Restricted source access.`, and the old Drive paragraph).
  - Change `"RESTRICTED"` fixtures to `"PRIVATE"` in `google-drive-panel.test.tsx` and `source-groups-section.test.tsx`.
  - Update e2e selectors that name `Restricted`.
- [x] **Step 7: Run** `pnpm check` in `web`. Expected: PASS.
- [x] **Step 8: Commit** as `feat(web): choose Public, Private or Auto Sync Source access (MEM-105)`, with the contract regeneration in its own commit.

### Task 6: ADR and documentation

- [x] Write ADR 0011, "Verified login email matches provider Source permissions":
  - **Context:** Auto Sync needs reader identity; `identity.md` excludes email from linking.
  - **Decision:** a verified login email, lower-cased, is compared with provider grants and never binds, admits or JIT-provisions.
  - **Consequences:** unverified readers see no Auto Sync documents; there are no email aliases.
- [x] `docs/specs/identity.md`: amend the email sentence to link ADR 0011.
- [x] `docs/specs/connector.md`:
  - line 7: FILE Public/Private;
  - line 10: Drive with three modes, default Auto Sync;
  - line 15: the browser access line;
  - line 39: remove "Groups or Drive PUBLIC control";
  - line 193: the boundary paragraph becomes enforcement by Auto Sync;
  - the state table's "Enforcement reading" column follows the approved freshness rule;
  - add a "Source access modes" section with the interpretation table and the token rule.
- [x] `docs/tests/connector.md` and `docs/tests/identity.md`: add rows for the new tests.
- [x] `docs/specs/document.md`, `ARCHITECTURE.md` (Source access and the Search access paragraph), `README.md` if it names RESTRICTED, and `docs/roadmap.md`.
- [x] `docs/increments/completed/mem-93-chat-search-authorization/plan.md`: link the deferred Google-token line to MEM-105.
- [x] Commit as `docs(connector): record Source access modes and ADR 0011 (MEM-105)`.

### Task 7: Verification

- [x] Run `.\gradlew.bat clean check` and `pnpm check`, and record the results in `verification.md`.
- [ ] **Live local runtime through Orca,** with the YOUNGXV AUTO Drive fixture:
  - two local accounts, one whose verified email is on the fixture ACL and one that is not;
  - switch the Source to Auto Sync, then Private (with and without Group membership), then Public;
  - check Search results and a Chat answer's citations for each account in each mode;
  - record counts only, with no email addresses, and change no staging permissions.
  - Partially done on 2026-09-15: the owner-only Auto Sync run is recorded in [verification.md](verification.md); a second reader is still open.
- [ ] Open the PR against the MEM-88 branch (retarget to `main` after #151 merges), update Linear MEM-105, and move the increment's durable facts into the specs.
