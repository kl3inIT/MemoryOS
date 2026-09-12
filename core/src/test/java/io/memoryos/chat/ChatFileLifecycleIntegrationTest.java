package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.chat.application.ChatFileProperties;
import io.memoryos.chat.application.DefaultUserFileWorkService;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.chat.persistence.JdbcUserFileWorkRepository;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.DocumentId;
import io.memoryos.document.persistence.JdbcDocumentRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.persistence.IamLockRepository;
import io.memoryos.iam.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.persistence.JpaTenantRepository;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.ingestion.persistence.JdbcOperationDispatchRepository;
import io.memoryos.objectstorage.*;
import io.memoryos.objectstorage.application.DefaultObjectUploadService;
import io.memoryos.objectstorage.application.ObjectUploadProperties;
import io.memoryos.objectstorage.persistence.JdbcObjectUploadRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL, migrations, IAM guards, adoption and claims; storage IO is a controlled test double. */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatFileLifecycleIntegrationTest {
    private static final String SHA = "a".repeat(64);
    private HikariDataSource database;
    private TestDatabase.JpaHarness jpa;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private ChatFileService files;
    private ChatFileContentService fileContent;
    private DefaultObjectUploadService uploads;
    private ObjectStorage storage;
    private UserFileWorkPort work;
    private DocumentCommandPort documents;
    private TenantId tenant;
    private ActorId owner;
    private ActorId other;

    @BeforeEach
    void setup() throws Exception {
        database = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(database);
        jpa = TestDatabase.jpa(database);
        tx = new TransactionTemplate(jpa.transactionManager());
        var tenants = TestDatabase.transactionalProxy(new JpaTenantAccessResolver(new JpaTenantRepository(jpa.entityManager()),
                new IamLockRepository(jdbc)), TenantAccessResolver.class, jpa.transactionManager());
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'Files','ACTIVE','test')")
                .param("id", tenant.value()).param("slug", tenant.value().toString()).update();
        owner = member(); other = member();
        storage = mock(ObjectStorage.class);
        when(storage.authorizeUpload(any(), any())).thenAnswer(ignored -> new UploadAuthorization("PUT",
                URI.create("https://storage.invalid/upload"), Map.of(), Instant.now().plusSeconds(300)));
        when(storage.inspect(any())).thenReturn(new ObjectMetadata(4, "text/plain", new ContentSha256(SHA)));
        uploads = new DefaultObjectUploadService(new JdbcStoredObjectRepository(jdbc), new JdbcObjectUploadRepository(jdbc),
                storage, new ObjectUploadProperties(Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofMinutes(1), 16), jpa.transactionManager());
        files = new ChatFileService(tenants, new JdbcChatRepository(jdbc), new JdbcUserFileRepository(jdbc), uploads,
                new ChatFileProperties(104857600,262144000), jpa.transactionManager());
        fileContent = new ChatFileContentService(new JdbcUserFileRepository(jdbc), tenants, storage);
        documents = TestDatabase.transactionalProxy(new JdbcDocumentRepository(jdbc, new ObjectMapper(), ignored -> {}),
                DocumentCommandPort.class, jpa.transactionManager());
        work = TestDatabase.transactionalProxy(new DefaultUserFileWorkService(new JdbcUserFileWorkRepository(jdbc), documents, uploads, tenants),
                UserFileWorkPort.class, jpa.transactionManager());
    }

    @AfterEach
    void close() {
        if (jpa != null) jpa.close();
        if (database != null) database.close();
    }

    @Test
    void uploadIdentityIsOwnerPrivateAndFinalizeQueuesExactlyOnce() {
        var input = input(UUID.randomUUID(), 4);
        var first = files.initiate(owner, input);
        assertEquals(first.file().id(), files.initiate(owner, input).file().id());
        assertThrows(ChatException.class, () -> files.get(other, first.file().id()));
        assertThrows(ChatException.class, () -> files.finalizeUpload(other, first.file().id()));
        assertThrows(ChatException.class, () -> files.initiate(owner, input(input.requestId(), 5)));
        var completed = files.finalizeUpload(owner, first.file().id());
        assertEquals(UserFile.Status.PROCESSING, completed.status());
        assertEquals(completed, files.finalizeUpload(owner, first.file().id()));
        assertNull(files.initiate(owner, input).upload());
        assertEquals(1, count("chat_user_file"));
        assertEquals(1, count("chat_file_work"));
        assertEquals("ADOPTED", jdbc.sql("SELECT status FROM object_uploads").query(String.class).single());
    }

    @Test
    void chatPurposeCannotBeVerifiedThroughTheSourceFilePathEvenForSmallFiles() {
        var receipt = files.initiate(owner, input(UUID.randomUUID(), 4));
        var id = new ObjectUploadId(jdbc.sql("SELECT upload_id FROM chat_user_file WHERE id=:id")
                .param("id", receipt.file().id()).query(UUID.class).single());
        assertEquals("OBJECT_UPLOAD_NOT_FOUND", assertThrows(ObjectUploadException.class, () -> uploads.verify(tenant, id)).code());
        verify(storage, never()).inspect(any());
    }

    @Test
    void mismatchedMetadataAndRevocationDuringProviderIoNeverAdopt() {
        var id = files.initiate(owner, input(UUID.randomUUID(), 4)).file().id();
        when(storage.inspect(any())).thenReturn(new ObjectMetadata(5,"text/plain",new ContentSha256(SHA)));
        assertThrows(ObjectUploadException.class, () -> files.finalizeUpload(owner, id));
        assertEquals(UserFile.Status.UPLOADING, files.get(owner, id).status());
        when(storage.inspect(any())).thenAnswer(ignored -> {
            jdbc.sql("UPDATE tenant_memberships SET status='INACTIVE' WHERE actor_id=:actor").param("actor",owner.value()).update();
            return new ObjectMetadata(4,"text/plain",new ContentSha256(SHA));
        });
        assertThrows(ChatException.class, () -> files.finalizeUpload(owner, id));
        assertEquals(0, count("chat_file_work"));
        assertEquals("VERIFIED", jdbc.sql("SELECT status FROM object_uploads").query(String.class).single());
    }

    @Test
    void contentPublicationIsFencedAndUserFileReferenceProtectsTheDocument() {
        var id = finalized();
        var delivery = dispatch();
        var claim = work.claim(tenant, delivery.operationId().value(), delivery.deliveryId()).orElseThrow();
        assertTrue(work.claim(tenant, delivery.operationId().value(), delivery.deliveryId()).isEmpty());
        assertTrue(work.complete(claim, content()));
        assertFalse(work.complete(claim, content()));
        var document = new DocumentId(jdbc.sql("SELECT document_id FROM chat_user_file WHERE id=:id").param("id",id).query(UUID.class).single());
        documents.removeUnreferenced(tenant, java.util.List.of(document));
        assertEquals(1, count("documents"));
        assertEquals(id.toString(),jdbc.sql("SELECT metadata_json::jsonb ->> 'user_file_id' FROM documents").query(String.class).single());
        assertEquals("hello",jdbc.sql("SELECT plaintext FROM chat_user_file WHERE id=:id").param("id",id).query(String.class).single());
        assertEquals(UserFile.Status.READY, files.get(owner,id).status());
        assertEquals(UserFile.Status.DELETING,files.delete(owner,id).status());
        var deletion = dispatch();
        var deleting = work.claim(tenant,deletion.operationId().value(),deletion.deliveryId()).orElseThrow();
        assertTrue(work.deleted(deleting));
        assertEquals(0,count("documents"));
        assertEquals(UserFile.Status.DELETED,files.get(owner,id).status());
        assertEquals(1,uploads.cleanupAbandoned());
        assertEquals(0,count("stored_objects"));
        assertEquals("EXPIRED",jdbc.sql("SELECT status FROM object_uploads").query(String.class).single());
    }

    @Test
    void deletionFencesAnAlreadyClaimedParseAndDoesNotResurrectContent() {
        var id = finalized(); var delivery = dispatch();
        var stale = work.claim(tenant,delivery.operationId().value(),delivery.deliveryId()).orElseThrow();
        files.delete(owner,id);
        assertFalse(work.renew(stale));
        assertFalse(work.complete(stale,content()));
        work.failed(stale,"FILE_PROCESSING_FAILED");
        assertEquals(UserFile.Status.DELETING,files.get(owner,id).status());
        assertEquals(0,count("documents"));
    }

    @Test
    void expiredWorkerClaimIsReclaimedAndLateWorkerCannotPublishOrFailTheReplacement() {
        var id = finalized();
        var delivery = dispatch();
        var stale = work.claim(tenant, delivery.operationId().value(), delivery.deliveryId()).orElseThrow();
        jdbc.sql("UPDATE chat_file_work SET lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE file_id=:id")
                .param("id", id).update();
        var current = work.claim(tenant, delivery.operationId().value(), delivery.deliveryId()).orElseThrow();
        assertNotEquals(stale.token(), current.token());
        assertFalse(work.renew(stale));
        assertFalse(work.complete(stale, content()));
        work.failed(stale, "FILE_PROCESSING_FAILED");
        assertTrue(work.complete(current, content()));
        assertEquals(UserFile.Status.READY, files.get(owner, id).status());
        assertEquals(1, count("documents"));
    }

    @Test
    void controlPlaneExpiresAbandonedUploadAndRetryRequiresNewUpload() {
        var id = files.initiate(owner, input(UUID.randomUUID(), 4)).file().id();
        jdbc.sql("UPDATE stored_objects SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE tenant_id=:tenant")
                .param("tenant", tenant.value()).update();
        assertEquals(1, uploads.cleanupAbandoned());
        var repository = new JdbcUserFileWorkRepository(jdbc);
        assertEquals(1, repository.expireUploads(100));
        assertEquals(0, repository.expireUploads(100));
        assertEquals(UserFile.Status.FAILED, files.get(owner, id).status());
        assertEquals("UPLOAD_EXPIRED", files.get(owner, id).errorCode());
        assertThrows(ChatException.class, () -> files.retry(owner, id));
        assertEquals(0, count("chat_file_work"));
    }

    @Test
    void uploadCapDoesNotChangeSourcePolicyAndResumeCannotReviveExpiredUpload() {
        assertDoesNotThrow(() -> files.initiate(owner,input(UUID.randomUUID(),104857600)));
        assertThrows(ChatException.class,() -> files.initiate(owner,input(UUID.randomUUID(),104857601)));
        assertThrows(IllegalArgumentException.class,() -> new ObjectUploadSpecification("a.txt","text/plain",104857601,new ContentSha256(SHA)));
        assertThrows(IllegalArgumentException.class,() -> new ChatFileProperties(262144001,262144000));
        var input = input(UUID.randomUUID(),4);
        files.initiate(owner,input);
        jdbc.sql("UPDATE stored_objects SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE tenant_id=:tenant")
                .param("tenant",tenant.value()).update();
        assertThrows(ObjectUploadException.class,() -> files.initiate(owner,input));
    }

    @Test
    void resumedAuthorizationCannotOutliveItsDurableCleanupReservation() {
        var input = input(UUID.randomUUID(),4);
        files.initiate(owner,input);
        Instant signedExpiry = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusNanos(123456100);
        when(storage.authorizeUpload(any(),any())).thenReturn(new UploadAuthorization("PUT",URI.create("https://storage.invalid/upload"),Map.of(),signedExpiry));
        var resumed = files.initiate(owner,input);
        assertNotNull(resumed.upload());
        Instant retained = jdbc.sql("SELECT expires_at FROM stored_objects").query(java.sql.Timestamp.class).single().toInstant();
        assertFalse(retained.isBefore(signedExpiry));
    }

    @Test
    void plaintextRangesUseUnicodeCodePointsAndNeverBypassOwnerOrDeletion() {
        var id = finalized();
        assertThrows(ChatException.class, () -> files.read(owner, id, 0, 10));
        var delivery = dispatch();
        var claim = work.claim(tenant, delivery.operationId().value(), delivery.deliveryId()).orElseThrow();
        assertTrue(work.complete(claim, new DocumentContent("text/plain", "test.txt", "A😀Việt", Map.of())));
        assertEquals(new ChatFileService.FileText("😀V", 1, 6), files.read(owner, id, 1, 2));
        assertEquals("iệt", files.read(owner, id, 3, 10).text());
        assertEquals("", files.read(owner, id, 6, 1).text());
        assertThrows(ChatException.class, () -> files.read(owner, id, -1, 1));
        assertThrows(ChatException.class, () -> files.read(owner, id, 0, 16001));
        assertThrows(ChatException.class, () -> files.read(other, id, 0, 10));
        assertThrows(ChatException.class, () -> files.read(owner, new TenantId(UUID.randomUUID()), id, 0, 10));
        files.delete(owner, id);
        assertThrows(ChatException.class, () -> files.read(owner, id, 0, 10));
    }

    @Test
    void originalContentClosesOnRevocationDuringStorageIoAndNeverOpensForAnotherOwner() {
        var id = finalized();
        var delivery = dispatch();
        var claim = work.claim(tenant, delivery.operationId().value(), delivery.deliveryId()).orElseThrow();
        assertTrue(work.complete(claim, content()));
        assertThrows(ChatException.class, () -> fileContent.open(other, id));
        verify(storage, never()).open(any());
        var stream = mock(ObjectContent.class);
        when(stream.metadata()).thenReturn(new ObjectMetadata(4, "text/plain", new ContentSha256(SHA)));
        when(storage.open(any())).thenReturn(stream);
        try (var opened = fileContent.open(owner, id)) { assertSame(stream, opened); }
        verify(stream).close();
        clearInvocations(stream);
        when(storage.open(any())).thenAnswer(ignored -> {
            jdbc.sql("UPDATE tenant_memberships SET status='INACTIVE' WHERE actor_id=:actor")
                    .param("actor", owner.value()).update();
            return stream;
        });
        assertThrows(ChatException.class, () -> fileContent.open(owner, id));
        verify(stream).close();
        assertThrows(ChatException.class, () -> files.read(owner, id, 0, 10));
    }

    @Test
    void originalContentClosesOnMetadataMismatchOrConcurrentDeletion() {
        var id = finalized();
        var delivery = dispatch();
        var claim = work.claim(tenant, delivery.operationId().value(), delivery.deliveryId()).orElseThrow();
        assertTrue(work.complete(claim, content()));
        var stream = mock(ObjectContent.class);
        when(stream.metadata()).thenReturn(new ObjectMetadata(5, "text/plain", new ContentSha256(SHA)));
        when(storage.open(any())).thenReturn(stream);
        assertThrows(ChatException.class, () -> fileContent.open(owner, id));
        verify(stream).close();
        clearInvocations(stream);
        when(stream.metadata()).thenReturn(new ObjectMetadata(4, "text/plain", new ContentSha256(SHA)));
        when(storage.open(any())).thenAnswer(ignored -> { files.delete(owner, id); return stream; });
        assertThrows(ChatException.class, () -> fileContent.open(owner, id));
        verify(stream).close();
    }

    private UUID finalized() {
        var id = files.initiate(owner,input(UUID.randomUUID(),4)).file().id();
        files.finalizeUpload(owner,id); return id;
    }

    private OperationDelivery dispatch() {
        return java.util.Objects.requireNonNull(tx.execute(ignored -> new JdbcOperationDispatchRepository(jdbc).claim(OperationWorkload.USER_FILE,1)))
                .getFirst().delivery();
    }
    private static ChatFileService.UploadInput input(UUID request,long size) {
        return new ChatFileService.UploadInput(request,"test.txt","text/plain",size,SHA);
    }
    private static DocumentContent content() { return new DocumentContent("text/plain","test.txt","hello",Map.of()); }
    private int count(String table) { return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single(); }
    private ActorId member() {
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id",actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant",tenant.value()).param("actor",actor.value()).update();
        return actor;
    }
}
