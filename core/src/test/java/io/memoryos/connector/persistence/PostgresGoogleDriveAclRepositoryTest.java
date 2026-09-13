package io.memoryos.connector.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAclService;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.GoogleDriveAclSnapshot;
import io.memoryos.connector.GoogleDriveAclSnapshot.ContextStatus;
import io.memoryos.connector.GoogleDriveAclSnapshot.Status;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveOAuthClient;
import io.memoryos.connector.GoogleDriveProvider.Permission;
import io.memoryos.connector.GoogleDriveProvider.PermissionDetail;
import io.memoryos.connector.GoogleDriveSourceService.Root;
import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresGoogleDriveAclRepositoryTest {
    private static final String FILE = "google-file";
    private static final String ROOT = "google-folder";
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private JdbcSourceRepository sources;
    private JdbcSourceSyncRepository sync;
    private JdbcGoogleDriveSourceRepository drive;
    private JdbcGoogleDriveCredentialRepository credentials;
    private JdbcGoogleDriveAclRepository acls;
    private Fixture fixture;

    @BeforeEach
    void initialize() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        sources = new JdbcSourceRepository(jdbc);
        sync = new JdbcSourceSyncRepository(jdbc);
        drive = new JdbcGoogleDriveSourceRepository(jdbc);
        credentials = new JdbcGoogleDriveCredentialRepository(jdbc, sources,
                new GoogleDriveCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "test"),
                new JdbcSourceDocumentRepository(jdbc), sync);
        acls = new JdbcGoogleDriveAclRepository(jdbc);
        fixture = source(tenant());
    }

    @AfterEach
    void closeDatabase() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void replacesCompleteObservationAndRetainsItWithItsProvenanceAfterFailure() {
        var first = permission("permission-user", "user", "reader");
        var group = permission("permission-group", "group", "writer");
        success(fixture, List.of(first, group));
        var initial = read(fixture);

        var replacement = permission("permission-domain", "domain", "reader");
        success(fixture, List.of(replacement));
        var complete = read(fixture);
        assertThat(complete.permissions()).containsExactly(replacement);
        assertThat(complete.revision()).isEqualTo(initial.revision() + 1);
        assertThat(complete.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(complete.contextStatus()).isEqualTo(ContextStatus.CURRENT);
        assertThat(complete.lastSuccess()).isEqualTo(complete.lastAttempt());
        assertThat(complete.lastAttempt().at()).isAfterOrEqualTo(initial.lastAttempt().at());

        Work next = nextWork(fixture);
        tx.executeWithoutResult(_ -> acls.recordFailure(next, FILE, "SOURCE_GOOGLE_PERMISSION_DENIED", null));
        var failed = read(fixture);
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("SOURCE_GOOGLE_PERMISSION_DENIED");
        assertThat(failed.permissions()).containsExactly(replacement);
        assertThat(failed.revision()).isEqualTo(complete.revision());
        assertThat(failed.lastSuccess()).isEqualTo(complete.lastSuccess());
        assertThat(failed.lastAttempt().operationId()).isEqualTo(next.operationId());
        assertThat(failed.lastAttempt().generation()).isEqualTo(next.generation());
        assertThat(failed.contextStatus()).isEqualTo(ContextStatus.STALE);
        assertThat(failed.readAt()).isAfterOrEqualTo(failed.lastAttempt().at());
    }

    @Test
    void rolledBackReplacementLeavesTheCommittedSnapshotAndRevisionUntouched() {
        success(fixture, List.of(permission("permission-user", "user", "reader")));
        var committed = read(fixture);
        tx.executeWithoutResult(transaction -> {
            acls.recordSuccess(fixture.work(), FILE, List.of());
            transaction.setRollbackOnly();
        });
        var retained = read(fixture);
        assertThat(retained.permissions()).isEqualTo(committed.permissions());
        assertThat(retained.revision()).isEqualTo(committed.revision());
        assertThat(retained.lastSuccess()).isEqualTo(committed.lastSuccess());
        assertThat(retained.status()).isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void distinguishesMissingFailedOnlyAndSuccessfulEmptyObservations() {
        assertThat(acls.read(fixture.tenant(), fixture.source(), FILE)).isEmpty();
        tx.executeWithoutResult(_ -> acls.recordFailure(fixture.work(), FILE, "SOURCE_GOOGLE_NOT_FOUND", null));
        var unknown = read(fixture);
        assertThat(unknown.status()).isEqualTo(Status.FAILED);
        assertThat(unknown.contextStatus()).isEqualTo(ContextStatus.UNOBSERVED);
        assertThat(unknown.lastSuccess()).isNull();
        assertThat(unknown.revision()).isZero();
        assertThat(unknown.permissions()).isEmpty();

        success(fixture, List.of());
        var empty = read(fixture);
        assertThat(empty.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(empty.contextStatus()).isEqualTo(ContextStatus.CURRENT);
        assertThat(empty.lastSuccess()).isNotNull();
        assertThat(empty.revision()).isEqualTo(1);
        assertThat(empty.permissions()).isEmpty();
        assertThat(empty.errorCode()).isNull();

        tx.executeWithoutResult(_ -> acls.recordFailure(fixture.work(), FILE, "SOURCE_GOOGLE_UNAVAILABLE", null));
        var failedEmpty = read(fixture);
        assertThat(failedEmpty.status()).isEqualTo(Status.FAILED);
        assertThat(failedEmpty.lastSuccess()).isEqualTo(empty.lastSuccess());
        assertThat(failedEmpty.revision()).isEqualTo(1);
        assertThat(failedEmpty.permissions()).isEmpty();
    }

    @Test
    void isolatesIdenticalProviderIdsAndDocumentMappingsByTenantAndSource() {
        var sibling = source(fixture.tenant());
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        var foreign = source(tenant());
        var ownPermission = permission("permission-own", "user", "reader");
        success(fixture, List.of(ownPermission));
        success(sibling, List.of(permission("permission-sibling", "group", "reader")));
        success(foreign, List.of(permission("permission-foreign", "domain", "reader")));
        var ownDocument = document(fixture);
        var siblingDocument = document(sibling);
        var foreignDocument = document(foreign);

        assertThat(read(fixture).permissions()).containsExactly(ownPermission);
        assertThat(read(fixture).documentIds()).containsExactly(ownDocument);
        assertThat(read(sibling).documentIds()).containsExactly(siblingDocument);
        assertThat(read(foreign).documentIds()).containsExactly(foreignDocument);
        assertThat(read(fixture).sourceItemId()).isNotNull();
        assertThat(acls.read(foreign.tenant(), fixture.source(), FILE)).isEmpty();
        assertThat(acls.read(fixture.tenant(), foreign.source(), FILE)).isEmpty();

        jdbc.sql("DELETE FROM google_drive_sources WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", fixture.tenant().value()).param("source", fixture.source().value()).update();
        assertThat(acls.read(fixture.tenant(), fixture.source(), FILE)).isEmpty();
        assertThat(read(sibling).documentIds()).containsExactly(siblingDocument);
        assertThat(read(foreign).documentIds()).containsExactly(foreignDocument);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "source", "connector"})
    void lifecycleDeactivationInvalidatesRetainedEvidence(String target) {
        success(fixture, List.of());
        switch (target) {
            case "tenant" -> jdbc.sql("UPDATE tenants SET status = 'INACTIVE' WHERE id = :id")
                    .param("id", fixture.tenant().value()).update();
            case "source" -> jdbc.sql("UPDATE connector_credential_pairs SET status = 'DELETING' WHERE id = :id")
                    .param("id", fixture.source().value()).update();
            case "connector" -> jdbc.sql("UPDATE connectors SET status = 'DELETING' WHERE id = :id")
                    .param("id", fixture.connector()).update();
            default -> throw new IllegalArgumentException(target);
        }
        assertThat(read(fixture).contextStatus()).isEqualTo(ContextStatus.INVALID);
        assertThat(read(fixture).lastSuccess()).isNotNull();
    }

    @Test
    void revokedCredentialInvalidatesEvidenceWithoutErasingSuccess() {
        success(fixture, List.of(permission("permission-user", "user", "reader")));
        var before = read(fixture);
        tx.executeWithoutResult(_ -> Arrays.fill(credentials.disconnect(fixture.tenant(), fixture.credential(), 1), (byte) 0));
        var revoked = read(fixture);
        assertThat(revoked.currentContext().credentialActive()).isFalse();
        assertThat(revoked.contextStatus()).isEqualTo(ContextStatus.INVALID);
        assertThat(revoked.lastSuccess()).isEqualTo(before.lastSuccess());
        assertThat(revoked.permissions()).isEqualTo(before.permissions());
    }

    @Test
    void renewedCredentialRevisionDoesNotMakeOldPermissionEvidenceCurrent() {
        success(fixture, List.of());
        jdbc.sql("""
                UPDATE google_drive_credentials SET credential_revision = credential_revision + 1
                WHERE tenant_id = :tenant AND credential_id = :credential
                """).param("tenant", fixture.tenant().value()).param("credential", fixture.credential().value()).update();
        var staleCredential = read(fixture);
        assertThat(staleCredential.currentContext().credentialActive()).isTrue();
        assertThat(staleCredential.currentContext().credentialRevision()).isEqualTo(2);
        assertThat(staleCredential.lastSuccess().credentialRevision()).isEqualTo(1);
        assertThat(staleCredential.contextStatus()).isEqualTo(ContextStatus.INVALID);
    }

    @Test
    void switchingCredentialIdentityAtTheSameRevisionInvalidatesOldEvidence() {
        success(fixture, List.of());
        var replacement = credential(fixture.tenant());
        jdbc.sql("UPDATE connector_credential_pairs SET credential_id = :credential WHERE tenant_id = :tenant AND id = :source")
                .param("credential", replacement.value()).param("tenant", fixture.tenant().value())
                .param("source", fixture.source().value()).update();
        var switched = read(fixture);
        assertThat(switched.currentContext().credentialRevision()).isEqualTo(1);
        assertThat(switched.currentContext().credentialId()).isEqualTo(replacement);
        assertThat(switched.lastSuccess().credentialId()).isEqualTo(fixture.credential());
        assertThat(switched.contextStatus()).isEqualTo(ContextStatus.INVALID);
    }

    @Test
    void replacingSelectionInvalidatesEvidenceEvenWhenTheSameRootRemains() {
        success(fixture, List.of());
        tx.executeWithoutResult(_ -> drive.replace(fixture.tenant(), fixture.source(), 1, ScopeMode.SPECIFIC,
                List.of(new Root(ROOT, "Folder", "application/vnd.google-apps.folder"))));
        var replaced = read(fixture);
        assertThat(replaced.currentContext().selected()).isTrue();
        assertThat(replaced.currentContext().scopeRevision()).isEqualTo(2);
        assertThat(replaced.lastSuccess().scopeRevision()).isEqualTo(1);
        assertThat(replaced.contextStatus()).isEqualTo(ContextStatus.INVALID);
    }

    @Test
    void newTraversalRemainsStaleUntilMembershipAndPermissionsAreObservedAgain() {
        success(fixture, List.of());
        Work next = nextWork(fixture);
        assertThat(read(fixture).contextStatus()).isEqualTo(ContextStatus.STALE);
        tx.executeWithoutResult(_ -> {
            sync.observe(next, FILE, ROOT, "version-1");
            acls.recordSuccess(next, FILE, List.of());
        });
        var refreshed = read(fixture);
        assertThat(refreshed.contextStatus()).isEqualTo(ContextStatus.CURRENT);
        assertThat(refreshed.currentContext().membershipGeneration()).isEqualTo(next.generation());
        assertThat(refreshed.lastSuccess().generation()).isEqualTo(next.generation());
        assertThat(refreshed.revision()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"excluded", "absent", "missing", "root-removed"})
    void membershipRemovalInvalidatesEvidence(String change) {
        success(fixture, List.of());
        String sql = switch (change) {
            case "excluded" -> "UPDATE google_drive_membership SET excluded = TRUE WHERE tenant_id = :tenant AND source_id = :source";
            case "absent" -> "UPDATE google_drive_membership SET root_id = NULL WHERE tenant_id = :tenant AND source_id = :source";
            case "missing" -> "DELETE FROM google_drive_membership WHERE tenant_id = :tenant AND source_id = :source";
            case "root-removed" -> "DELETE FROM google_drive_roots WHERE tenant_id = :tenant AND source_id = :source";
            default -> throw new IllegalArgumentException(change);
        };
        jdbc.sql(sql).param("tenant", fixture.tenant().value()).param("source", fixture.source().value()).update();
        assertThat(read(fixture).contextStatus()).isEqualTo(ContextStatus.INVALID);
        assertThat(read(fixture).currentContext().selected()).isFalse();
    }

    @Test
    void removingLinkedApprovalInvalidatesAnOtherwiseRetainedMembership() {
        jdbc.sql("""
                INSERT INTO google_drive_linked_documents (tenant_id, source_id, file_id, name, mime_type, status, covered_by_roots)
                VALUES (:tenant, :source, :file, 'Linked file', 'application/pdf', 'AVAILABLE', FALSE)
                """).param("tenant", fixture.tenant().value()).param("source", fixture.source().value()).param("file", FILE).update();
        jdbc.sql("INSERT INTO google_drive_link_approvals (tenant_id, source_id, file_id) VALUES (:tenant, :source, :file)")
                .param("tenant", fixture.tenant().value()).param("source", fixture.source().value()).param("file", FILE).update();
        tx.executeWithoutResult(_ -> {
            sync.observe(fixture.work(), FILE, FILE, "version-1");
            acls.recordSuccess(fixture.work(), FILE, List.of());
        });
        assertThat(read(fixture).contextStatus()).isEqualTo(ContextStatus.CURRENT);
        jdbc.sql("DELETE FROM google_drive_link_approvals WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", fixture.tenant().value()).param("source", fixture.source().value()).update();
        assertThat(read(fixture).contextStatus()).isEqualTo(ContextStatus.INVALID);
    }

    @Test
    void itemDeletionAndPersistentExclusionPreventEvidenceBecomingCurrentAfterCleanup() {
        success(fixture, List.of());
        document(fixture);
        SourceItemId item = Objects.requireNonNull(read(fixture).sourceItemId());
        jdbc.sql("UPDATE connector_items SET status = 'DELETING' WHERE tenant_id = :tenant AND id = :item")
                .param("tenant", fixture.tenant().value()).param("item", item.value()).update();
        assertThat(read(fixture).currentContext().itemRemoved()).isTrue();
        assertThat(read(fixture).contextStatus()).isEqualTo(ContextStatus.INVALID);
        tx.executeWithoutResult(_ -> {
            sync.exclude(fixture.tenant(), fixture.source(), item);
            jdbc.sql("DELETE FROM documents_by_connector_credential_pair WHERE tenant_id = :tenant AND connector_item_id = :item")
                    .param("tenant", fixture.tenant().value()).param("item", item.value()).update();
            jdbc.sql("DELETE FROM connector_items WHERE tenant_id = :tenant AND id = :item")
                    .param("tenant", fixture.tenant().value()).param("item", item.value()).update();
        });
        var removed = read(fixture);
        assertThat(removed.sourceItemId()).isNull();
        assertThat(removed.documentIds()).isEmpty();
        assertThat(removed.contextStatus()).isEqualTo(ContextStatus.INVALID);
    }

    @Test
    void rawProviderFailureTextCannotReplaceSafeEvidence() {
        success(fixture, List.of());
        assertThatThrownBy(() -> tx.executeWithoutResult(_ -> acls.recordFailure(fixture.work(), FILE,
                "request rejected for private@example.test", null))).isInstanceOf(IllegalArgumentException.class);
        assertThat(read(fixture).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(read(fixture).errorCode()).isNull();
    }

    @Test
    void inspectorDistinguishesAbsentFailedOnlyAndSuccessfulEmptyBeforeDocumentsExist() {
        assertThat(acls.get(fixture.tenant(), fixture.source(), FILE).orElseThrow().snapshot()).isNull();
        var absent = aclItem();
        assertThat(absent.status()).isNull();
        assertThat(absent.contextStatus()).isNull();
        assertThat(absent.revision()).isNull();
        assertThat(absent.permissionCount()).isNull();
        assertThat(absent.lastAttemptAt()).isNull();

        tx.executeWithoutResult(_ -> acls.recordFailure(fixture.work(), FILE, "SOURCE_GOOGLE_NOT_FOUND", null));
        var failed = aclItem();
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.contextStatus()).isEqualTo(ContextStatus.UNOBSERVED);
        assertThat(failed.permissionCount()).isNull();
        assertThat(failed.lastSuccessAt()).isNull();
        assertThat(failed.lastAttemptAt()).isNotNull();
        assertThat(acls.get(fixture.tenant(), fixture.source(), FILE).orElseThrow().snapshot().lastSuccess()).isNull();

        success(fixture, List.of());
        var empty = aclItem();
        assertThat(empty.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(empty.contextStatus()).isEqualTo(ContextStatus.CURRENT);
        assertThat(empty.permissionCount()).isZero();
        assertThat(empty.lastSuccessAt()).isEqualTo(empty.lastAttemptAt());
        var detail = acls.get(fixture.tenant(), fixture.source(), FILE).orElseThrow().snapshot();
        assertThat(detail.permissions()).isEmpty();
        assertThat(detail.sourceItemId()).isNull();
        assertThat(detail.documentIds()).isEmpty();

        tx.executeWithoutResult(_ -> acls.recordFailure(fixture.work(), FILE, "SOURCE_GOOGLE_UNAVAILABLE", null));
        assertThat(aclItem().status()).isEqualTo(Status.FAILED);
        assertThat(aclItem().permissionCount()).isZero();
        assertThat(aclItem().lastSuccessAt()).isEqualTo(empty.lastSuccessAt());
    }

    @Test
    void inspectorProjectsStaleAndInvalidContextWhileRetainingUnselectedSnapshots() {
        success(fixture, List.of(permission("reader", "user", "reader")));
        nextWork(fixture);
        assertThat(aclItem().contextStatus()).isEqualTo(ContextStatus.STALE);
        assertThat(acls.get(fixture.tenant(), fixture.source(), FILE).orElseThrow().snapshot().contextStatus())
                .isEqualTo(ContextStatus.STALE);
        jdbc.sql("DELETE FROM google_drive_membership WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", fixture.tenant().value()).param("source", fixture.source().value()).update();
        assertThat(aclItem().contextStatus()).isEqualTo(ContextStatus.INVALID);
        assertThat(aclItem().permissionCount()).isEqualTo(1);
        assertThat(acls.get(fixture.tenant(), fixture.source(), FILE).orElseThrow().snapshot().permissions())
                .extracting(Permission::id).containsExactly("reader");
    }

    @Test
    void inspectorPagesKnownFoldersAndMembershipWithLiteralSearchAndScopedCursors() {
        jdbc.sql("""
                INSERT INTO google_drive_membership (tenant_id, source_id, file_id, root_id, generation)
                SELECT :tenant, :source, 'member-' || lpad(n::text, 2, '0'), :root, 1 FROM generate_series(1, 26) n
                """).param("tenant", fixture.tenant().value()).param("source", fixture.source().value()).param("root", ROOT).update();
        var first = acls.list(fixture.tenant(), fixture.source(), new GoogleDriveAclService.Query(null, 25, null));
        assertThat(first.totalItems()).isEqualTo(28);
        assertThat(first.items()).extracting(GoogleDriveAclService.Item::fileId).contains(FILE, ROOT);
        assertThat(first.items()).filteredOn(item -> item.fileId().equals(ROOT)).singleElement()
                .satisfies(folder -> assertThat(folder.name()).isEqualTo("Folder"));
        var next = acls.list(fixture.tenant(), fixture.source(), new GoogleDriveAclService.Query(first.nextCursor(), 25, null));
        assertThat(next.items()).extracting(GoogleDriveAclService.Item::fileId).containsExactly("member-24", "member-25", "member-26");
        assertThat(next.nextCursor()).isNull();
        assertThat(next.totalItems()).isEqualTo(28);
        assertThat(acls.list(fixture.tenant(), fixture.source(), new GoogleDriveAclService.Query(null, 25, "MEMBER-2")).totalItems())
                .isEqualTo(7);
        assertThatThrownBy(() -> acls.list(fixture.tenant(), fixture.source(),
                new GoogleDriveAclService.Query(first.nextCursor(), 25, "changed"))).isInstanceOf(SourceException.class);
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        var other = source(tenant());
        assertThatThrownBy(() -> acls.list(other.tenant(), other.source(),
                new GoogleDriveAclService.Query(first.nextCursor(), 25, null))).isInstanceOf(SourceException.class);
        assertThat(acls.list(other.tenant(), fixture.source(), new GoogleDriveAclService.Query(null, 25, null)).totalItems()).isZero();
        assertThat(acls.get(other.tenant(), fixture.source(), FILE)).isEmpty();
        assertThat(acls.get(fixture.tenant(), fixture.source(), "unknown")).isEmpty();

        jdbc.sql("UPDATE google_drive_roots SET name = '100%_! complete' WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", fixture.tenant().value()).param("source", fixture.source().value()).update();
        assertThat(acls.list(fixture.tenant(), fixture.source(), new GoogleDriveAclService.Query(null, 25, "%_!")).items())
                .extracting(GoogleDriveAclService.Item::fileId).containsExactly(ROOT);
        assertThat(acls.list(fixture.tenant(), fixture.source(), new GoogleDriveAclService.Query(null, 25, "no match")).totalItems()).isZero();
    }

    private GoogleDriveAclService.Item aclItem() {
        return acls.list(fixture.tenant(), fixture.source(), new GoogleDriveAclService.Query(null, 25, FILE)).items().getFirst();
    }

    private TenantId tenant() {
        var tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'ACL fixture', 'ACTIVE', :reference)")
                .param("id", tenant.value()).param("slug", "acl-" + tenant.value()).param("reference", "ACL-" + tenant.value()).update();
        return tenant;
    }

    private Fixture source(TenantId tenant) {
        var pair = Objects.requireNonNull(tx.execute(_ -> sources.createFileSource(tenant, "Drive ACL fixture")));
        var credential = credential(tenant);
        jdbc.sql("UPDATE connectors SET connector_type = 'GOOGLE_DRIVE' WHERE tenant_id = :tenant AND id = :connector")
                .param("tenant", tenant.value()).param("connector", pair.connectorId()).update();
        jdbc.sql("UPDATE connector_credential_pairs SET credential_id = :credential, access_type = 'RESTRICTED' WHERE tenant_id = :tenant AND id = :source")
                .param("credential", credential.value()).param("tenant", tenant.value()).param("source", pair.sourceId().value()).update();
        jdbc.sql("INSERT INTO google_drive_sources (tenant_id, source_id) VALUES (:tenant, :source)")
                .param("tenant", tenant.value()).param("source", pair.sourceId().value()).update();
        jdbc.sql("INSERT INTO google_drive_roots (tenant_id, source_id, file_id, name, mime_type) VALUES (:tenant, :source, :root, 'Folder', 'application/vnd.google-apps.folder')")
                .param("tenant", tenant.value()).param("source", pair.sourceId().value()).param("root", ROOT).update();
        Work work = work(tenant, pair.sourceId());
        tx.executeWithoutResult(_ -> sync.observe(work, FILE, ROOT, "version-1"));
        return new Fixture(tenant, pair.sourceId(), pair.connectorId(), credential, work);
    }

    private CredentialId credential(TenantId tenant) {
        try (var client = new GoogleDriveOAuthClient("fixture.apps.googleusercontent.com", "fixture-secret".getBytes(StandardCharsets.UTF_8));
                var grant = new GoogleDriveAuthorizationService.Grant("fixture-subject", "fixture@example.test",
                        GoogleDriveAuthorizationService.REQUIRED_SCOPES, "fixture-refresh".getBytes(StandardCharsets.UTF_8))) {
            return Objects.requireNonNull(tx.execute(_ -> credentials.create(tenant, "ACL fixture credential", grant, client)));
        }
    }

    private Work work(TenantId tenant, SourceId source) {
        return Objects.requireNonNull(tx.execute(_ -> {
            var operation = sync.enqueue(tenant, source, 1, SourceRunTrigger.MANUAL, null);
            UUID delivery = UUID.randomUUID();
            jdbc.sql("UPDATE source_sync_attempts SET delivery_id = :delivery WHERE tenant_id = :tenant AND id = :operation")
                    .param("delivery", delivery).param("tenant", tenant.value()).param("operation", operation.id().value()).update();
            return sync.claim(tenant, operation.id(), delivery).orElseThrow();
        }));
    }

    private Work nextWork(Fixture value) {
        tx.executeWithoutResult(_ -> sync.cancel(value.tenant(), value.source()));
        return work(value.tenant(), value.source());
    }

    private void success(Fixture value, List<Permission> permissions) {
        tx.executeWithoutResult(_ -> acls.recordSuccess(value.work(), FILE, permissions));
    }

    private GoogleDriveAclSnapshot read(Fixture value) {
        return acls.read(value.tenant(), value.source(), FILE).orElseThrow();
    }

    private DocumentId document(Fixture value) {
        UUID item = UUID.randomUUID();
        var document = new DocumentId(UUID.randomUUID());
        tx.executeWithoutResult(_ -> {
            jdbc.sql("INSERT INTO connector_items (id, tenant_id, connector_id, content_sha256, status, provider_file_id) VALUES (:item, :tenant, :connector, :sha, 'INDEXED', :file)")
                    .param("item", item).param("tenant", value.tenant().value()).param("connector", value.connector())
                    .param("sha", "a".repeat(64)).param("file", FILE).update();
            jdbc.sql("INSERT INTO documents (id, tenant_id, status) VALUES (:document, :tenant, 'ELIGIBLE')")
                    .param("document", document.value()).param("tenant", value.tenant().value()).update();
            jdbc.sql("""
                    INSERT INTO documents_by_connector_credential_pair (tenant_id, connector_id, connector_credential_pair_id,
                        document_id, connector_item_id, retrieval_eligible)
                    VALUES (:tenant, :connector, :source, :document, :item, FALSE)
                    """).param("tenant", value.tenant().value()).param("connector", value.connector())
                    .param("source", value.source().value()).param("document", document.value()).param("item", item).update();
        });
        return document;
    }

    private static Permission permission(String id, String type, String role) {
        return new Permission(id, type, role, "private@example.test", "example.test", Instant.parse("2030-01-01T00:00:00Z"),
                false, false, true, List.of(new PermissionDetail("member", role, "shared-drive-folder", true)), "published", true);
    }

    private record Fixture(TenantId tenant, SourceId source, UUID connector, CredentialId credential, Work work) {}
}
