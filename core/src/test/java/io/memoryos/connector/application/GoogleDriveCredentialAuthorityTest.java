package io.memoryos.connector.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveSourceService;
import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.memoryos.connector.CleanupWork;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveAuthorizationService.Grant;
import io.memoryos.connector.GoogleDriveConnectionService;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.GoogleDriveOAuthClient;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveLinkReader;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.persistence.GoogleDriveCredentialConfiguration;
import io.memoryos.connector.persistence.JdbcGoogleDriveCredentialRepository;
import io.memoryos.connector.persistence.JdbcGoogleDriveSourceRepository;
import io.memoryos.connector.persistence.JdbcCleanupAttemptRepository;
import io.memoryos.connector.persistence.JdbcSourceSyncRepository;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.connector.persistence.JdbcGoogleDriveSelectionRepository;
import io.memoryos.connector.GoogleDriveSelectionProcessor;
import io.memoryos.connector.SourceOperationStatus;
import io.memoryos.connector.GoogleDriveSourceService.SelectionReceipt;
import io.memoryos.identity.ActorId;
import io.memoryos.tenant.TenantId;
import io.memoryos.tenant.persistence.JdbcTenantAccessResolver;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class GoogleDriveCredentialAuthorityTest {
    private HikariDataSource dataSource;

    @AfterEach
    void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private JdbcClient jdbc;
    private JdbcGoogleDriveCredentialRepository credentials;
    private GoogleDriveAuthorizationService authorizations;
    private GoogleDriveConnectionService connections;
    private GoogleDriveProvider provider;
    private TransactionTemplate transactions;
    private TenantId tenant;
    private ActorId owner;
    private GoogleDriveSourceService drive;
    private JdbcGoogleDriveSourceRepository roots;
    private JdbcSourceRepository sources;
    private JdbcSourceSyncRepository sync;
    private final GoogleDriveLinkReader linkReader = mock(GoogleDriveLinkReader.class);
    private JdbcGoogleDriveSelectionRepository selections;
    private GoogleDriveSelectionProcessor processor;

    @BeforeEach
    void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        tenant = new TenantId(UUID.randomUUID()); owner = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors (id) VALUES (:id)").param("id", owner.value()).update();
        jdbc.sql("INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference) VALUES (:id, 'drive-auth', 'Drive', 'ACTIVE', 'TEST')")
                .param("id", tenant.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships (tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'OWNER', 'ACTIVE')")
                .param("tenant", tenant.value()).param("actor", owner.value()).update();
        sources = new JdbcSourceRepository(jdbc);
        roots = new JdbcGoogleDriveSourceRepository(jdbc);
        sync = new JdbcSourceSyncRepository(jdbc);
        var documents = new JdbcSourceDocumentRepository(jdbc);
        credentials = new JdbcGoogleDriveCredentialRepository(jdbc, sources,
                new GoogleDriveCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "test-v1"), documents, sync);
        provider = mock(GoogleDriveProvider.class);
        connections = TestDatabase.transactionalProxy(new DefaultGoogleDriveConnectionService(credentials, provider, manager),
                GoogleDriveConnectionService.class, manager);
        var attempts = new JdbcIndexAttemptRepository(jdbc, sources, documents, connections);
        authorizations = TestDatabase.transactionalProxy(new DefaultGoogleDriveAuthorizationService(credentials,
                new JdbcTenantAccessResolver(jdbc)), GoogleDriveAuthorizationService.class, manager);
        selections = new JdbcGoogleDriveSelectionRepository(jdbc);
        var service = new DefaultGoogleDriveSourceService(new JdbcTenantAccessResolver(jdbc), connections, roots, sources,
                sync, attempts, documents, linkReader, manager, selections, credentials, new GoogleDriveSelectionPolicy(1000, 3145728));
        drive = service;
        processor = new DefaultGoogleDriveSelectionProcessor(selections, service, connections, manager);
    }

    @Test
    void completedAuthorizationCreatesIndependentOwnerTenantBoundCredentialsWithoutSources() {
        var prepared = prepare();
        try (var grant = grant("first")) {
            var credential = authorizations.complete(owner, prepared, grant);
            var second = authorizations.complete(owner, prepare(), grant);
            assertNotEquals(credential, second);
            assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM connectors").query(Integer.class).single());
            var catalog = authorizations.list(owner);
            assertEquals(java.util.Set.of(credential, second), catalog.stream().map(GoogleDriveAuthorizationService.CredentialView::id).collect(java.util.stream.Collectors.toSet()));
            assertTrue(catalog.stream().allMatch(row -> row.sourceCount() == 0));
            assertThrows(SourceException.class, () -> authorizations.prepare(new ActorId(UUID.randomUUID()), "Drive", credential, 1L, null));
            assertThrows(SourceException.class, () -> connections.openCredential(new TenantId(UUID.randomUUID()), credential));
            assertThrows(SourceException.class, () -> authorizations.complete(owner,
                    new GoogleDriveAuthorizationService.Preparation(new TenantId(UUID.randomUUID()), "Drive", null, null,
                            prepared.consentId(), prepared.oauthClientSnapshot()), grant));
        }
    }

    @Test
    void rejectsMissingDocsScopeBeforeAnySourceOrCredentialRows() {
        var scopes = scopes(); scopes.remove("https://www.googleapis.com/auth/documents.readonly");
        try (var grant = new Grant("subject", "owner@example.com", scopes, "secret".getBytes(StandardCharsets.UTF_8))) {
            assertThrows(SourceException.class, () -> authorizations.complete(owner, prepare(), grant));
        }
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM credentials").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM connectors").query(Integer.class).single());
    }

    @Test
    void obsoleteRefreshAndAuthenticationFailureCannotOverwriteReauthorization() {
        SourceId source = connect();
        when(provider.open(any())).thenAnswer(_ -> {
            reauthorize(source, 1, "new-grant");
            throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.AUTHENTICATION);
        });
        assertThrows(SourceException.class, () -> connections.open(tenant, source));
        assertEquals("ACTIVE", connections.state(tenant, source).status());
        assertEquals(2, connections.state(tenant, source).credentialRevision());
        assertFalse(Boolean.TRUE.equals(transactions.execute(_ -> credentials.rotate(tenant, credential(source), 1, 1, "stale".getBytes(StandardCharsets.UTF_8)))));
        assertArrayEquals("new-grant".getBytes(StandardCharsets.UTF_8), credentials.decrypt(tenant, credentials.readUsable(tenant, credential(source))));
    }

    @Test
    void rotatedRefreshPreservesPublicationAuthorityAndPersistsTheNewToken() {
        SourceId source = connect();
        var session = mock(GoogleDriveProvider.Session.class);
        when(session.rotatedRefreshToken()).thenReturn("rotated".getBytes(StandardCharsets.UTF_8));
        when(provider.open(any())).thenReturn(session);
        try (var opened = connections.open(tenant, source)) {
            assertEquals(1, opened.credentialRevision());
            assertTrue(current(source, 1));
        }
        assertArrayEquals("rotated".getBytes(StandardCharsets.UTF_8), credentials.decrypt(tenant, credentials.readUsable(tenant, credential(source))));
        assertThrows(IllegalTransactionStateException.class, () -> connections.current(tenant, source, 1));
    }

    @Test
    void obsoleteRefreshFailureCannotInvalidateAWinningTokenRotation() {
        SourceId source = connect();
        when(provider.open(any())).thenAnswer(_ -> {
            assertTrue(Boolean.TRUE.equals(transactions.execute(_ ->
                    credentials.rotate(tenant, credential(source), 1, 1, "winner".getBytes(StandardCharsets.UTF_8)))));
            throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.AUTHENTICATION);
        });
        assertThrows(SourceException.class, () -> connections.open(tenant, source));
        assertTrue(current(source, 1));
        assertFalse(Boolean.TRUE.equals(transactions.execute(_ ->
                credentials.rotate(tenant, credential(source), 1, 1, "stale".getBytes(StandardCharsets.UTF_8)))));
        assertArrayEquals("winner".getBytes(StandardCharsets.UTF_8),
                credentials.decrypt(tenant, credentials.readUsable(tenant, credential(source))));
    }

    @Test
    void disconnectDestroysLocalGrantAndCanReconnectWithoutDeletingSource() {
        SourceId source = connect();
        byte[] token = authorizations.disconnect(owner, credential(source), 1);
        assertArrayEquals("initial".getBytes(StandardCharsets.UTF_8), token);
        assertFalse(current(source, 1));
        assertEquals("REVOKED", connections.state(tenant, source).status());
        assertTrue(connections.state(tenant, source).oauthClientConfigured());
        assertNull(transactions.execute(_ -> credentials.lock(tenant, credential(source)).orElseThrow().ciphertext()));
        try (var app = authorizations.oauthClient(owner, authorizations.prepare(owner, "Drive", credential(source), 2L, null))) {
            assertEquals("owner-app.apps.googleusercontent.com", app.clientId());
            assertArrayEquals(bytes("owner-app-secret"), app.clientSecret());
        }
        assertThrows(GoogleDriveException.class, () -> connections.open(tenant, source));
        reauthorize(source, 2, "reconnected");
        assertTrue(current(source, 3));
        assertThrows(SourceException.class, () -> authorizations.disconnect(owner, credential(source), 1));
    }

    @Test
    void lostEncryptionKeyCannotPreventLocalDisconnect() {
        SourceId source = connect();
        jdbc.sql("UPDATE google_drive_credentials SET key_version = 'unavailable' WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).update();
        assertThrows(GoogleDriveException.class, () -> connections.open(tenant, source));
        assertEquals(0, authorizations.disconnect(owner, credential(source), 1).length);
        assertEquals("REVOKED", connections.state(tenant, source).status());
        assertFalse(current(source, 1));
    }

    @Test
    void publicationOnOneSourceExcludesSharedReauthorizationThroughAnotherUntilCommit() throws Exception {
        SourceId source = connect();
        var shared = credential(source);
        SourceId second = transactions.execute(_ -> {
            assertTrue(connections.currentCredential(tenant, shared, 1));
            return roots.create(tenant, new SourceId(UUID.randomUUID()), "Other Source", shared, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(new GoogleDriveSourceService.Root("other", "Other", "text/plain")));
        });
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var publication = executor.submit(() -> transactions.execute(_ -> {
                assertTrue(connections.current(tenant, source, 1));
                locked.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                return true;
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var replacement = executor.submit(() -> reauthorize(second, 1, "replacement"));
            try { assertThrows(TimeoutException.class, () -> replacement.get(100, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            assertTrue(publication.get(5, TimeUnit.SECONDS));
            replacement.get(5, TimeUnit.SECONDS);
            assertFalse(current(source, 1));
            assertTrue(current(source, 2));
        } finally { release.countDown(); }
    }

    @Test
    void consentSnapshotAuthenticatesOwnerTenantTargetAndConsentWithoutPlaintextSecrets() {
        var preparation = prepare();
        assertFalse(preparation.oauthClientSnapshot().contains("owner-app-secret"));
        assertFalse(preparation.oauthClientSnapshot().contains("owner-app.apps.googleusercontent.com"));
        try (var client = authorizations.oauthClient(owner, preparation)) {
            assertArrayEquals(bytes("owner-app-secret"), client.clientSecret());
        }
        assertThrows(GoogleDriveException.class, () -> credentials.snapshot(new ActorId(UUID.randomUUID()), preparation));
        for (var tampered : new GoogleDriveAuthorizationService.Preparation[]{
                new GoogleDriveAuthorizationService.Preparation(new TenantId(UUID.randomUUID()), "Drive", null, null,
                        preparation.consentId(), preparation.oauthClientSnapshot()),
                new GoogleDriveAuthorizationService.Preparation(tenant, "Drive", null, null,
                        UUID.randomUUID(), preparation.oauthClientSnapshot()),
                new GoogleDriveAuthorizationService.Preparation(tenant, "Drive", new CredentialId(UUID.randomUUID()), 1L,
                        preparation.consentId(), preparation.oauthClientSnapshot())}) {
            assertThrows(GoogleDriveException.class, () -> credentials.snapshot(owner, tampered));
        }
    }

    @Test
    void storedAppIsEncryptedAndReplacementIsPinnedToItsConsent() {
        SourceId source = connect();
        byte[] stored = jdbc.sql("SELECT oauth_client_ciphertext FROM google_drive_credentials").query((row, _) -> row.getBytes(1)).single();
        assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("owner-app-secret"));
        var reused = authorizations.prepare(owner, "Drive", credential(source), 1L, null);
        GoogleDriveAuthorizationService.Preparation replacement;
        try (var app = new GoogleDriveOAuthClient("replacement.apps.googleusercontent.com", bytes("replacement-secret"))) {
            replacement = authorizations.prepare(owner, "Drive", credential(source), 1L, app);
        }
        try (var original = authorizations.oauthClient(owner, reused); var next = authorizations.oauthClient(owner, replacement)) {
            assertEquals("owner-app.apps.googleusercontent.com", original.clientId());
            assertEquals("replacement.apps.googleusercontent.com", next.clientId());
        }
        try (var grant = grant("replacement-grant")) { authorizations.complete(owner, replacement, grant); }
        assertThrows(SourceException.class, () -> authorizations.oauthClient(owner, reused));
        try (var grant = grant("stale-grant")) { assertThrows(SourceException.class, () -> authorizations.complete(owner, reused, grant)); }
        when(provider.open(any())).thenAnswer(invocation -> {
            GoogleDriveProvider.Credential client = invocation.getArgument(0);
            assertEquals("replacement.apps.googleusercontent.com", client.clientId());
            assertArrayEquals(bytes("replacement-secret"), client.clientSecret());
            assertArrayEquals(bytes("replacement-grant"), client.refreshToken());
            return mock(GoogleDriveProvider.Session.class);
        });
        try (var connection = connections.open(tenant, source)) { assertEquals(2, connection.credentialRevision()); }
    }

    @Test
    void replacingAppCannotChangePreviouslyAuthorizedGoogleSubject() {
        SourceId source = connect();
        try (var app = new GoogleDriveOAuthClient("replacement.apps.googleusercontent.com", bytes("replacement-secret"));
             var grant = new Grant("different-subject", "other@example.com", scopes(), bytes("other-grant"))) {
            var preparation = authorizations.prepare(owner, "Drive", credential(source), 1L, app);
            assertThrows(SourceException.class, () -> authorizations.complete(owner, preparation, grant));
        }
        assertTrue(current(source, 1));
        try (var app = authorizations.oauthClient(owner, authorizations.prepare(owner, "Drive", credential(source), 1L, null))) {
            assertEquals("owner-app.apps.googleusercontent.com", app.clientId());
        }
    }

    @Test
    void legacyCredentialWithoutAppCannotOpenOrReconnectImplicitly() {
        SourceId source = connect();
        jdbc.sql("""
                UPDATE google_drive_credentials SET connection_status = 'NEEDS_REAUTHORIZATION',
                    oauth_client_ciphertext = NULL, oauth_client_nonce = NULL, oauth_client_key_version = NULL,
                    refresh_token_ciphertext = NULL, refresh_token_nonce = NULL, key_version = NULL
                """).update();
        assertFalse(connections.state(tenant, source).oauthClientConfigured());
        assertThrows(GoogleDriveException.class, () -> connections.open(tenant, source));
        assertEquals("GOOGLE_DRIVE_OAUTH_CLIENT_REQUIRED",
                assertThrows(GoogleDriveException.class, () -> authorizations.prepare(owner, "Drive", credential(source), 1L, null)).code());
        verifyNoInteractions(provider);
        try (var app = new GoogleDriveOAuthClient("owner-app.apps.googleusercontent.com", bytes("owner-app-secret")); var grant = grant("reauthorized")) {
            authorizations.complete(owner, authorizations.prepare(owner, "Drive", credential(source), 1L, app), grant);
        }
        assertTrue(current(source, 2));
        assertTrue(connections.state(tenant, source).oauthClientConfigured());
    }

    @Test
    void oneCredentialCreatesIndependentSourcesAndSurvivesTheirCleanup() {
        var id = authorize();
        mockSelection();
        SourceId first = create(owner, "First", id, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("one")));
        SourceId second = create(owner, "Second", id, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("two")));
        assertNotEquals(first, second);
        assertEquals(id, drive.configuration(owner, first).credentialId());
        assertEquals(id, drive.configuration(owner, second).credentialId());
        assertEquals(List.of(link("one")), drive.selectionDraft(owner, first).links());
        assertEquals(List.of(link("two")), drive.selectionDraft(owner, second).links());
        assertTrue(drive.configuration(owner, first).pendingWork());
        assertTrue(drive.configuration(owner, second).pendingWork());
        assertEquals(2, authorizations.list(owner).getFirst().sourceCount());
        assertThrows(SourceException.class, () -> authorizations.delete(owner, id, 1));
        replace(owner, first, 1, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("three")), List.of());
        assertEquals(1, drive.configuration(owner, second).revision());
        assertEquals(List.of(link("two")), drive.selectionDraft(owner, second).links());
        cleanup(first);
        assertEquals(1, authorizations.list(owner).getFirst().sourceCount());
        cleanup(second);
        var remaining = authorizations.list(owner).getFirst();
        assertEquals(id, remaining.id());
        assertEquals(0, remaining.sourceCount());
        assertThrows(SourceException.class, () -> authorizations.delete(owner, id, 2));
        try (var connection = connections.openCredential(tenant, id)) {
            assertEquals(1, connection.credentialRevision());
        }
        authorizations.delete(owner, id, 1);
        assertTrue(authorizations.list(owner).isEmpty());
    }

    @Test
    void generalCreationUsesTheCredentialsVerifiedRootWithoutExposingASyntheticSelection() {
        var id = authorize();
        var session = mockSelection();
        doAnswer(_ -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return new GoogleDriveProvider.FileMetadata("own-root", "My Drive", "application/vnd.google-apps.folder",
                    "1", null, null, false, List.of(), null, null);
        }).when(session).metadata("root");

        var source = create(owner, "General source", id, ScopeMode.GENERAL, List.of());

        var configuration = drive.configuration(owner, source);
        assertEquals(id, configuration.credentialId());
        assertEquals(ScopeMode.GENERAL, configuration.scopeMode());
        assertTrue(drive.selectionDraft(owner, source).links().isEmpty());
        assertTrue(configuration.pendingWork());
        assertEquals(List.of("own-root"), roots.roots(tenant, source).stream().map(GoogleDriveSourceService.Root::id).toList());
        assertEquals(1, authorizations.list(owner).getFirst().sourceCount());
    }

    @Test
    void invalidScopeAndMixedLinksCannotCreateOrReplaceAnAcceptedSelection() {
        var id = authorize();
        mockSelection();
        var source = create(owner, "Specific source", id, ScopeMode.SPECIFIC, List.of(link("one")));

        assertThrows(SourceException.class, () -> create(owner, "Missing mode", id, null, List.of(link("two"))));
        assertThrows(SourceException.class, () -> create(owner, "Mixed", id, ScopeMode.GENERAL, List.of(link("two"))));
        assertThrows(SourceException.class, () -> create(owner, "Null links", id, ScopeMode.GENERAL, null));
        assertThrows(SourceException.class, () -> create(owner, "Empty specific", id, ScopeMode.SPECIFIC, List.of()));
        assertThrows(SourceException.class, () -> replace(owner, source, 1, null, List.of(link("two")), List.of()));
        assertThrows(SourceException.class, () -> replace(owner, source, 1, ScopeMode.GENERAL, List.of(link("two")), List.of()));
        assertThrows(SourceException.class, () -> replace(owner, source, 1, ScopeMode.GENERAL, null, List.of()));
        assertThrows(SourceException.class, () -> replace(owner, source, 1, ScopeMode.SPECIFIC, List.of(), List.of()));

        var configuration = drive.configuration(owner, source);
        assertEquals(ScopeMode.SPECIFIC, configuration.scopeMode());
        assertEquals(1, configuration.revision());
        assertEquals(List.of(link("one")), drive.selectionDraft(owner, source).links());
        assertEquals(1, authorizations.list(owner).getFirst().sourceCount());
    }

    @ParameterizedTest
    @EnumSource(ScopeMode.class)
    void scopeModeChangesAreRejectedBeforeProviderAccessAndPreserveSourceAuthority(ScopeMode savedMode) {
        var id = authorize();
        var session = mockSelection();
        doReturn(new GoogleDriveProvider.FileMetadata("own-root", "My Drive", "application/vnd.google-apps.folder",
                "1", null, null, false, List.of(), null, null)).when(session).metadata("root");
        var source = create(owner, "Immutable mode", id, savedMode,
                savedMode == ScopeMode.GENERAL ? List.of() : List.of(link("one")));
        drive.updateSchedule(owner, source, 1, 17);
        seedPublished(source);
        var configuration = drive.configuration(owner, source);
        var before = preservedScheduleState();
        var due = nextSyncAt(source);
        var requestedMode = savedMode == ScopeMode.GENERAL ? ScopeMode.SPECIFIC : ScopeMode.GENERAL;
        clearInvocations(provider, session);
        when(provider.open(any())).thenThrow(new GoogleDriveProviderException(GoogleDriveProviderException.Failure.AUTHENTICATION));

        var rejected = assertThrows(SourceException.class, () -> replace(owner, source, 1, requestedMode, requestedMode == ScopeMode.GENERAL ? List.of() : List.of(link("two")), List.of()));

        assertEquals("SOURCE_INVALID_REQUEST", rejected.code());
        assertEquals(configuration, drive.configuration(owner, source));
        assertEquals(before, preservedScheduleState());
        assertEquals(due, nextSyncAt(source));
        assertTrue(current(source, 1));
        verifyNoInteractions(provider, session);

        var repositoryRejected = assertThrows(SourceException.class, () -> roots.replace(tenant, source, 1, requestedMode,
                List.of(new GoogleDriveSourceService.Root("two", "Two", "text/plain"))));
        assertEquals("SOURCE_INVALID_REQUEST", repositoryRejected.code());
        assertEquals(configuration, drive.configuration(owner, source));
        assertEquals(before, preservedScheduleState());
        assertEquals(due, nextSyncAt(source));
        assertEquals("SOURCE_GOOGLE_REVISION_CONFLICT", assertThrows(SourceException.class,
                () -> roots.replace(tenant, source, 0, savedMode, List.of())).code());
        assertEquals(before, preservedScheduleState());
        assertEquals(due, nextSyncAt(source));
    }

    @ParameterizedTest
    @ValueSource(strings = {"alias", "invalid-id", "nonfolder", "trashed", "shared-drive", "shortcut", "nested", "nameless"})
    void generalRootValidationRejectsUnverifiedShapesWithoutChangingSelection(String shape) {
        var id = authorize();
        var session = mockSelection();
        var source = create(owner, "Specific source", id, ScopeMode.SPECIFIC, List.of(link("one")));
        var root = new GoogleDriveProvider.FileMetadata(
                "alias".equals(shape) ? "root" : "invalid-id".equals(shape) ? "invalid/id" : "own-root",
                "nameless".equals(shape) ? "" : "My Drive",
                "nonfolder".equals(shape) ? "text/plain" : "application/vnd.google-apps.folder", "1",
                null, null, "trashed".equals(shape), "nested".equals(shape) ? List.of("parent") : List.of(),
                "shared-drive".equals(shape) ? "own-root" : null, "shortcut".equals(shape) ? "target" : null);
        doReturn(root).when(session).metadata("root");

        var rejected = drive.create(owner, UUID.randomUUID(), "Rejected General", id, ScopeMode.GENERAL, List.of());
        assertEquals(SourceOperationStatus.FAILED, process(rejected).status());

        assertEquals(1, drive.configuration(owner, source).revision());
        assertEquals(ScopeMode.SPECIFIC, drive.configuration(owner, source).scopeMode());
        assertEquals(List.of("one"), roots.roots(tenant, source).stream().map(GoogleDriveSourceService.Root::id).toList());
        assertEquals(1, authorizations.list(owner).getFirst().sourceCount());
    }

    @Test
    void generalCreationRechecksCredentialAuthorityAfterRootResolution() {
        var id = authorize();
        var session = mockSelection();
        doAnswer(_ -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            authorizations.disconnect(owner, id, 1);
            return new GoogleDriveProvider.FileMetadata("own-root", "My Drive", "application/vnd.google-apps.folder",
                    "1", null, null, false, List.of(), null, null);
        }).when(session).metadata("root");

        var rejected = drive.create(owner, UUID.randomUUID(), "Racing General", id, ScopeMode.GENERAL, List.of());
        assertEquals(SourceOperationStatus.SUPERSEDED, process(rejected).status());

        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM connector_credential_pairs").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM source_sync_attempts").query(Integer.class).single());
        assertEquals("REVOKED", authorizations.list(owner).getFirst().status());
    }

    @Test
    void failedOrConcurrentRootValidationCannotLeavePartialSourceRows() {
        var id = authorize();
        var session = mockSelection();
        assertThrows(SourceException.class, () -> create(owner, "Invalid", id, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of("https://example.com/file")));
        when(session.metadata("missing")).thenThrow(new GoogleDriveProviderException(GoogleDriveProviderException.Failure.NOT_FOUND));
        assertEquals(SourceOperationStatus.FAILED, process(drive.create(owner, UUID.randomUUID(), "Missing", id,
                ScopeMode.SPECIFIC, List.of(link("missing")))).status());
        when(session.metadata("oversized")).thenReturn(new GoogleDriveProvider.FileMetadata(
                "oversized", "x".repeat(256), "text/plain", "1", null, null, false, List.of(), null, null));
        assertEquals(SourceOperationStatus.FAILED, process(drive.create(owner, UUID.randomUUID(), "Oversized", id,
                ScopeMode.SPECIFIC, List.of(link("oversized")))).status());
        when(session.metadata("racing")).thenAnswer(_ -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            authorizations.disconnect(owner, id, 1);
            return metadata("racing");
        });
        assertEquals(SourceOperationStatus.SUPERSEDED, process(drive.create(owner, UUID.randomUUID(), "Racing", id,
                ScopeMode.SPECIFIC, List.of(link("racing")))).status());
        for (String table : List.of("connectors", "connector_credential_pairs", "google_drive_sources", "google_drive_roots", "source_sync_attempts")) {
            assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single());
        }
        assertEquals(id, authorizations.list(owner).getFirst().id());
        assertEquals("REVOKED", authorizations.list(owner).getFirst().status());
    }

    @Test
    void sharedReauthorizationRevokeAndAuthenticationFailureFenceEveryAttachedSource() {
        var id = authorize();
        mockSelection();
        var first = create(owner, "First", id, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("one")));
        var second = create(owner, "Second", id, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("two")));
        seedPublished(first);
        seedPublished(second);
        reauthorize(first, 1, "replacement");
        for (var source : List.of(first, second)) {
            assertFalse(current(source, 1));
            assertTrue(current(source, 2));
            assertFalse(drive.configuration(owner, source).pendingWork());
        }
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM documents_by_connector_credential_pair WHERE retrieval_eligible").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM google_drive_membership WHERE eligible").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM index_attempts WHERE status IN ('NOT_STARTED','IN_PROGRESS')").query(Integer.class).single());
        drive.synchronize(owner, first);
        drive.synchronize(owner, second);
        connections.authenticationFailed(tenant, second, 2);
        for (var source : List.of(first, second)) {
            assertFalse(current(source, 2));
            assertEquals("NEEDS_REAUTHORIZATION", drive.configuration(owner, source).credentialStatus());
            assertEquals(3, drive.configuration(owner, source).credentialRevision());
            assertFalse(drive.configuration(owner, source).pendingWork());
        }
        reauthorize(first, 3, "recovered");
        drive.synchronize(owner, first);
        drive.synchronize(owner, second);
        authorizations.disconnect(owner, id, 4);
        for (var source : List.of(first, second)) {
            assertFalse(current(source, 4));
            assertEquals("REVOKED", drive.configuration(owner, source).credentialStatus());
            assertFalse(drive.configuration(owner, source).pendingWork());
        }
    }

    @Test
    void inactiveTenantLosesWorkerAuthorityWithoutPreventingSourceCleanup() {
        var source = connect();
        var id = credential(source);
        jdbc.sql("UPDATE tenants SET status = 'INACTIVE' WHERE id = :tenant")
                .param("tenant", tenant.value()).update();
        assertFalse(current(source, 1));
        assertFalse(Boolean.TRUE.equals(transactions.execute(_ -> connections.currentCredential(tenant, id, 1))));
        assertFalse(Boolean.TRUE.equals(transactions.execute(_ -> connections.currentCredential(new TenantId(UUID.randomUUID()), id, 1))));
        assertFalse(Boolean.TRUE.equals(transactions.execute(_ -> credentials.rotate(tenant, id, 1, 1, bytes("obsolete")))));
        assertFalse(Boolean.TRUE.equals(transactions.execute(_ -> credentials.refreshFailed(tenant, id, 1, 1))));
        connections.authenticationFailed(tenant, source, 1);
        assertEquals("ACTIVE", jdbc.sql("SELECT connection_status FROM google_drive_credentials")
                .query(String.class).single());
        cleanup(source);
        assertFalse(current(source, 1));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM connector_credential_pairs").query(Integer.class).single());
        assertEquals(id.value(), jdbc.sql("SELECT credential_id FROM google_drive_credentials").query(UUID.class).single());
    }

    @Test
    void scheduleEditsPreserveSharedCredentialScopeDocumentsAndLiveWork() {
        var id = authorize();
        mockSelection();
        var first = create(owner, "First", id, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("one")));
        var second = create(owner, "Second", id, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("two")));
        seedPublished(first);
        seedPublished(second);
        var before = preservedScheduleState();
        var otherDue = nextSyncAt(second);
        var savedAfter = databaseTime();
        clearInvocations(provider);

        var updated = drive.updateSchedule(owner, first, 1, 17);

        var savedBefore = databaseTime();
        assertEquals(17, updated.syncIntervalMinutes());
        assertEquals(2, updated.scheduleRevision());
        assertEquals(1, updated.revision());
        assertEquals(List.of(link("one")), drive.selectionDraft(owner, first).links());
        assertTrue(updated.pendingWork());
        assertEquals(before, preservedScheduleState());
        assertEquals(otherDue, nextSyncAt(second));
        assertEquals(5, drive.configuration(owner, second).syncIntervalMinutes());
        assertEquals(1, drive.configuration(owner, second).scheduleRevision());
        assertFalse(nextSyncAt(first).isBefore(savedAfter.plus(Duration.ofMinutes(17))));
        assertFalse(nextSyncAt(first).isAfter(savedBefore.plus(Duration.ofMinutes(17))));
        var acceptedDue = nextSyncAt(first);
        assertEquals("SOURCE_GOOGLE_REVISION_CONFLICT",
                assertThrows(SourceException.class, () -> drive.updateSchedule(owner, first, 1, 30)).code());
        assertEquals(acceptedDue, nextSyncAt(first));
        assertEquals(17, drive.configuration(owner, first).syncIntervalMinutes());
        verifyNoInteractions(provider);
    }

    @Test
    void scheduleCanChangeWithRevokedOrUnreadableCredentialsWithoutEnqueuingWork() {
        var source = connect();
        authorizations.disconnect(owner, credential(source), 1);
        var revoked = drive.updateSchedule(owner, source, 1, 1);
        assertEquals("REVOKED", revoked.credentialStatus());
        assertEquals(2, revoked.credentialRevision());
        assertEquals(1, revoked.syncIntervalMinutes());
        assertFalse(revoked.pendingWork());
        reauthorize(source, 2, "new-grant");
        jdbc.sql("UPDATE google_drive_credentials SET key_version = 'unavailable' WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).update();
        var before = preservedScheduleState();
        var updated = drive.updateSchedule(owner, source, 2, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, updated.syncIntervalMinutes());
        assertEquals(3, updated.scheduleRevision());
        assertFalse(updated.pendingWork());
        assertEquals(before, preservedScheduleState());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM source_sync_attempts").query(Integer.class).single());
        verifyNoInteractions(provider);
    }

    @Test
    void scheduleRejectsInvalidIntervalsAndInactiveOrDeletingAuthority() {
        var source = connect();
        for (int interval : new int[]{0, -1}) {
            assertEquals("SOURCE_INVALID_REQUEST",
                    assertThrows(SourceException.class, () -> drive.updateSchedule(owner, source, 1, interval)).code());
        }
        var before = nextSyncAt(source);
        jdbc.sql("UPDATE tenant_memberships SET role = 'MEMBER' WHERE actor_id = :actor")
                .param("actor", owner.value()).update();
        assertEquals("SOURCE_NOT_OWNER",
                assertThrows(SourceException.class, () -> drive.updateSchedule(owner, source, 1, 15)).code());
        jdbc.sql("UPDATE tenant_memberships SET role = 'OWNER', status = 'INACTIVE' WHERE actor_id = :actor")
                .param("actor", owner.value()).update();
        assertEquals("SOURCE_NOT_OWNER",
                assertThrows(SourceException.class, () -> drive.updateSchedule(owner, source, 1, 15)).code());
        jdbc.sql("UPDATE tenant_memberships SET status = 'ACTIVE' WHERE actor_id = :actor")
                .param("actor", owner.value()).update();
        jdbc.sql("UPDATE tenants SET status = 'INACTIVE' WHERE id = :tenant").param("tenant", tenant.value()).update();
        assertEquals("SOURCE_NOT_OWNER",
                assertThrows(SourceException.class, () -> drive.updateSchedule(owner, source, 1, 15)).code());
        jdbc.sql("UPDATE tenants SET status = 'ACTIVE' WHERE id = :tenant").param("tenant", tenant.value()).update();
        transactions.executeWithoutResult(_ -> sources.markDeleting(tenant, sources.lock(tenant, source)));
        assertEquals("SOURCE_NOT_FOUND",
                assertThrows(SourceException.class, () -> drive.updateSchedule(owner, source, 1, 15)).code());
        assertEquals(before, nextSyncAt(source));
        assertEquals(1, roots.configuration(tenant, source).scheduleRevision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"schedule", "discovery", "selection"})
    void sourceCommandsRecheckOwnerAfterWaitingForTheSourceLock(String command) throws Exception {
        var source = connect();
        var checked = new CountDownLatch(1);
        var tenants = spy(new JdbcTenantAccessResolver(jdbc));
        doAnswer(_ -> {
            var result = new JdbcTenantAccessResolver(jdbc).findActiveOwnerTenant(owner);
            checked.countDown();
            return result;
        }).when(tenants).findActiveOwnerTenant(owner);
        var documents = new JdbcSourceDocumentRepository(jdbc);
        var service = new DefaultGoogleDriveSourceService(tenants, connections, roots, sources, sync, new JdbcIndexAttemptRepository(jdbc, sources, documents, connections), documents, org.mockito.Mockito.mock(io.memoryos.connector.GoogleDriveLinkReader.class), java.util.Objects.requireNonNull(transactions.getTransactionManager()), selections, credentials, new GoogleDriveSelectionPolicy(1000, 3145728));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var update = transactions.execute(_ -> {
                sources.lock(tenant, source);
                var future = executor.submit(() -> switch (command) {
                    case "schedule" -> service.updateSchedule(owner, source, 1, 15);
                    case "discovery" -> service.discoverLinkedDocuments(owner, source, 1);
                    case "selection" -> service.replaceRoots(owner, UUID.randomUUID(), source, 1, 0, 1, ScopeMode.SPECIFIC, List.of(link("selected")), List.of());
                    default -> throw new AssertionError(command);
                });
                try {
                    assertTrue(checked.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                jdbc.sql("UPDATE tenant_memberships SET role = 'MEMBER' WHERE actor_id = :actor")
                        .param("actor", owner.value()).update();
                return future;
            });
            assertNotNull(update);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> update.get(5, TimeUnit.SECONDS));
            assertEquals("SOURCE_NOT_OWNER", ((SourceException) failure.getCause()).code());
        }
        assertEquals(1, roots.configuration(tenant, source).scheduleRevision());
        assertEquals(5, roots.configuration(tenant, source).syncIntervalMinutes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "credential", "roots", "deleting"})
    void discoveryRejectsAuthorityLostDuringAcquisitionWithoutPublishingCandidates(String change) {
        var source = connect();
        var session = mockDiscovery();
        doAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            switch (change) {
                case "owner" -> jdbc.sql("UPDATE tenant_memberships SET role='MEMBER' WHERE actor_id=:actor").param("actor", owner.value()).update();
                case "credential" -> reauthorize(source, 1, "replaced");
                case "roots" -> replace(owner, source, 1, ScopeMode.SPECIFIC, List.of(link("replacement")), List.of());
                case "deleting" -> transactions.executeWithoutResult(_ -> sources.markDeleting(tenant, sources.lock(tenant, source)));
                default -> throw new AssertionError(change);
            }
            return acquired(invocation.getArgument(0));
        }).when(session).acquire(any());
        assertThrows(SourceException.class, () -> drive.discoverLinkedDocuments(owner, source, 1));
        assertTrue(roots.linkedDocuments(tenant, source).isEmpty());
        assertNull(roots.configuration(tenant, source).discoveredAt());
    }

    @Test
    void olderDiscoveryCannotOverwriteANewerDiscoveryAndDoesNotAdvanceScopeRevision() {
        var source = connect();
        var session = mockDiscovery();
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.getAndSet(false)) drive.discoverLinkedDocuments(owner, source, 1);
            return acquired(invocation.getArgument(0));
        }).when(session).acquire(any());
        assertEquals("SOURCE_GOOGLE_REVISION_CONFLICT",
                assertThrows(SourceException.class, () -> drive.discoverLinkedDocuments(owner, source, 1)).code());
        var accepted = drive.configuration(owner, source);
        assertEquals(1, accepted.revision());
        assertEquals(1, accepted.discoveryRevision());
        var page = drive.selection(owner, source, null, GoogleDriveSourceService.SelectionKind.LINKED, null, 25);
        assertEquals(List.of("remote"), page.items().stream().map(GoogleDriveSourceService.SelectionItem::id).toList());
        assertFalse(page.items().getFirst().selected());
    }

    @Test
    void freshApprovalRechecksCredentialAfterMetadataAndRevocationPreservesOnlyExistingApprovals() {
        var source = connect();
        var session = mockDiscovery();
        drive.discoverLinkedDocuments(owner, source, 1);
        doAnswer(_ -> {
            reauthorize(source, 1, "changed-during-validation");
            return metadata("remote");
        }).when(session).metadata("remote");
        var proposedDraft = drive.selectionDraft(owner, source);
        var proposed = drive.replaceRoots(owner, UUID.randomUUID(), source, 1,
                proposedDraft.discoveryRevision(), proposedDraft.credentialRevision(),
                ScopeMode.SPECIFIC, List.of(link("selected")), List.of("remote"));
        assertEquals(SourceOperationStatus.SUPERSEDED, process(proposed).status());
        assertTrue(roots.approvedIds(tenant, source).isEmpty());
        assertEquals(1, roots.configuration(tenant, source).revision());
        assertThrows(SourceException.class,
                () -> replace(owner, source, 1, ScopeMode.SPECIFIC, List.of(link("selected")), List.of("remote")));

        doReturn(metadata("remote")).when(session).metadata("remote");
        drive.discoverLinkedDocuments(owner, source, 1);
        replace(owner, source, 1, ScopeMode.SPECIFIC, List.of(link("selected")), List.of("remote"));
        authorizations.disconnect(owner, credential(source), 2);
        var selected = drive.selection(owner, source, null, GoogleDriveSourceService.SelectionKind.LINKED, null, 25).items().getFirst();
        assertTrue(selected.selected());
        assertEquals("remote", selected.name());
        assertTrue(selected.origins().isEmpty());
        assertEquals(GoogleDriveSourceService.LinkedDocumentStatus.UNAVAILABLE, selected.status());
        cleanup(source);
        for (String table : List.of("google_drive_linked_documents", "google_drive_link_origins",
                "google_drive_link_approvals", "google_drive_discovery_errors")) {
            assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single());
        }
    }

    private GoogleDriveProvider.Session mockDiscovery() {
        var session = mockSelection();
        when(session.acquire(any())).thenAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return acquired(invocation.getArgument(0));
        });
        when(linkReader.read(any())).thenReturn(List.of(new GoogleDriveLinkReader.Link(link("remote"), "Paragraph 1")));
        return session;
    }

    private static GoogleDriveProvider.AcquiredContent acquired(GoogleDriveProvider.FileMetadata file) {
        return new GoogleDriveProvider.AcquiredContent(file.name(), "text/plain", bytes("linked source"),
                new SourceInputDescriptor(SourceInputFormat.BINARY, file.id(), file.version(), link(file.id())));
    }

    private Map<String, List<String>> preservedScheduleState() {
        var snapshots = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("credentials", "google_drive_credentials", "connectors", "connector_credential_pairs",
                "google_drive_roots", "google_drive_membership", "source_sync_attempts", "connector_items",
                "connector_item_versions", "documents", "documents_by_connector_credential_pair", "index_attempts")) {
            snapshots.put(table, jdbc.sql("SELECT row_to_json(r)::text FROM " + table + " r ORDER BY row_to_json(r)::text")
                    .query(String.class).list());
        }
        snapshots.put("google_drive_sources", jdbc.sql("""
                SELECT (to_jsonb(s) - 'sync_interval_minutes' - 'schedule_revision' - 'next_sync_at')::text
                FROM google_drive_sources s ORDER BY source_id
                """).query(String.class).list());
        return snapshots;
    }

    private Instant nextSyncAt(SourceId source) {
        return jdbc.sql("SELECT next_sync_at FROM google_drive_sources WHERE source_id = :source")
                .param("source", source.value()).query((row, _) -> row.getTimestamp(1).toInstant()).single();
    }

    private Instant databaseTime() {
        return jdbc.sql("SELECT clock_timestamp()").query((row, _) -> row.getTimestamp(1).toInstant()).single();
    }

    private CredentialId authorize() {
        try (var grant = grant("initial")) { return authorizations.complete(owner, prepare(), grant); }
    }

    private GoogleDriveProvider.Session mockSelection() {
        var session = mock(GoogleDriveProvider.Session.class);
        when(provider.open(any())).thenReturn(session);
        when(session.metadata(any())).thenAnswer(i -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return metadata(i.getArgument(0));
        });
        return session;
    }

    private static GoogleDriveProvider.FileMetadata metadata(String id) {
        return new GoogleDriveProvider.FileMetadata(id, id, "text/plain", "1", null, null, false, List.of(), null, null);
    }

    private static String link(String id) { return "https://drive.google.com/file/d/" + id + "/view"; }

    private void cleanup(SourceId source) {
        transactions.executeWithoutResult(_ -> {
            var pair = sources.lock(tenant, source);
            sources.markDeleting(tenant, pair);
            new JdbcCleanupAttemptRepository(jdbc).deleteSourceRows(new CleanupWork(new SourceOperationId(UUID.randomUUID()),
                    tenant, SourceOperationType.DELETE_SOURCE, source, null, UUID.randomUUID(), null), pair.connectorId());
        });
    }

    private void seedPublished(SourceId source) {
        transactions.executeWithoutResult(_ -> {
            var pair = sources.lock(tenant, source);
            var item = UUID.randomUUID();
            var file = roots.roots(tenant, source).getFirst().id();
            for (String sql : List.of(
                    "INSERT INTO stored_objects (id, tenant_id, object_key, filename, declared_media_type, size_bytes, content_sha256, state, expires_at) VALUES (:id, :t, CAST(:id AS TEXT), 'File.txt', 'text/plain', 1, REPEAT('a',64), 'ACTIVE', CURRENT_TIMESTAMP)",
                    "INSERT INTO connector_items (id, tenant_id, connector_id, content_sha256, status) VALUES (:id, :t, :c, REPEAT('a',64), 'INDEXED')",
                    "INSERT INTO connector_item_versions (id, tenant_id, connector_id, connector_item_id, revision_number, filename, content_sha256, size_bytes, stored_object_id) VALUES (:id, :t, :c, :id, 1, 'File.txt', REPEAT('a',64), 1, :id)",
                    "UPDATE connector_items SET current_version_id = :id WHERE tenant_id = :t AND id = :id",
                    "INSERT INTO documents (id, tenant_id, status, title) VALUES (:id, :t, 'ELIGIBLE', 'Document')",
                    "INSERT INTO documents_by_connector_credential_pair (tenant_id, connector_id, connector_credential_pair_id, document_id, connector_item_id, retrieval_eligible) VALUES (:t, :c, :s, :id, :id, TRUE)",
                    "INSERT INTO index_attempts (id, tenant_id, connector_id, connector_credential_pair_id, connector_item_id, connector_item_version_id, pair_sequence, item_sequence, status, claim_token, delivery_id) VALUES (:id, :t, :c, :s, :id, :id, 1, 1, 'IN_PROGRESS', :id, :id)",
                    "INSERT INTO google_drive_membership (tenant_id, source_id, file_id, root_id, generation, eligible) VALUES (:t, :s, :file, :file, 1, TRUE)")) {
                jdbc.sql(sql).param("id", item).param("t", tenant.value()).param("c", pair.connectorId())
                        .param("s", source.value()).param("file", file).update();
            }
        });
    }

    private GoogleDriveAuthorizationService.Preparation prepare() {
        try (var app = new GoogleDriveOAuthClient("owner-app.apps.googleusercontent.com", bytes("owner-app-secret"))) {
            return authorizations.prepare(owner, "Drive", null, null, app);
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private boolean current(SourceId source, long revision) {
        return Boolean.TRUE.equals(transactions.execute(_ -> connections.current(tenant, source, revision)));
    }
    private CredentialId credential(SourceId source) { return connections.state(tenant, source).credentialId(); }
    private SourceId connect() {
        try (var grant = grant("initial")) {
            var id = authorizations.complete(owner, prepare(), grant);
            return transactions.execute(_ -> {
                assertTrue(connections.currentCredential(tenant, id, 1));
                return roots.create(tenant, new SourceId(UUID.randomUUID()), "Drive Source", id, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(new GoogleDriveSourceService.Root("selected", "Selected", "text/plain")));
            });
        }
    }
    private CredentialId reauthorize(SourceId source, long revision, String token) {
        try (var grant = grant(token)) { return authorizations.complete(owner, authorizations.prepare(owner, "Drive", credential(source), revision, null), grant); }
    }
    private static Grant grant(String token) { return new Grant("subject", "owner@example.com", scopes(), token.getBytes(StandardCharsets.UTF_8)); }
    private static HashSet<String> scopes() {
        var scopes = new HashSet<>(GoogleDriveAuthorizationService.REQUIRED_SCOPES); scopes.add("email"); return scopes;
    }

    private SourceId create(ActorId actor, String name, CredentialId credential, ScopeMode mode, List<String> links) {
        var receipt = drive.create(actor, UUID.randomUUID(), name, credential, mode, links);
        assertEquals(SourceOperationStatus.SUCCEEDED, process(receipt).status());
        return receipt.sourceId();
    }

    private void replace(ActorId actor, SourceId source, long revision, ScopeMode mode, List<String> links, List<String> approvals) {
        var draft = drive.selectionDraft(actor, source);
        var receipt = drive.replaceRoots(actor, UUID.randomUUID(), source, revision,
                draft.discoveryRevision(), draft.credentialRevision(), mode, links, approvals);
        assertEquals(SourceOperationStatus.SUCCEEDED, process(receipt).status());
    }

    private io.memoryos.connector.SourceOperationView process(SelectionReceipt receipt) {
        for (int batch = 0; batch < 200; batch++) {
            var operation = selections.find(tenant, receipt.operation().id()).orElseThrow();
            if (operation.status() != SourceOperationStatus.NOT_STARTED && operation.status() != SourceOperationStatus.IN_PROGRESS) return operation;
            UUID delivery = UUID.randomUUID();
            jdbc.sql("UPDATE google_drive_selection_operations SET delivery_id=:delivery WHERE id=:id")
                    .param("delivery", delivery).param("id", operation.id().value()).update();
            processor.execute(processor.claim(tenant, operation.id(), delivery).orElseThrow());
        }
        throw new AssertionError("Selection did not terminate");
    }
}
