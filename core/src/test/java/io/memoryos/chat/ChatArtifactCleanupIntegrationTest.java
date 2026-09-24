package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.chat.image.ImageArtifactService;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.chat.interpreter.InterpreterProperties;
import io.memoryos.chat.interpreter.persistence.JdbcInterpreterRepository;
import io.memoryos.chat.files.persistence.JdbcChatArtifactCleanupRepository;
import io.memoryos.chat.image.persistence.JdbcImageArtifactRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.tenant.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.application.DefaultObjectWriteService;
import io.memoryos.objectstorage.application.DefaultStoredObjectRegistry;
import io.memoryos.objectstorage.application.ObjectUploadProperties;
import io.memoryos.objectstorage.persistence.JdbcObjectWriteRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import io.memoryos.library.LibraryStorageProperties;
import io.memoryos.library.StorageQuotaService;
import io.memoryos.library.ImageThumbnails;
import io.memoryos.library.persistence.JdbcLibraryRepository;
import io.memoryos.library.LibraryTrashProperties;
import io.memoryos.library.LibraryFile;

/**
 * Deleting a Chat artifact must release its bytes: the objects are adopted writes, which the generic reapers
 * never select. Real PostgreSQL and adoption; storage IO is a controlled double.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatArtifactCleanupIntegrationTest {
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private HikariDataSource database;
    private TestDatabase.JpaHarness jpa;
    private JdbcClient jdbc;
    private ObjectStorage storage;
    private InterpreterService interpreter;
    private ImageArtifactService images;
    private ChatArtifactCleanupService cleanup;
    private DefaultObjectWriteService writes;
    private JdbcInterpreterRepository artifacts;
    private JdbcImageArtifactRepository pictures;
    private TenantId tenant;
    private ActorId owner;
    private UUID messageId;
    private final java.util.Map<String, byte[]> stored = new java.util.HashMap<>();

    @BeforeEach
    void setup() throws Exception {
        database = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(database);
        jpa = TestDatabase.jpa(database);
        storage = mock(ObjectStorage.class);
        // A staged write is verified by reading it back, so the double remembers what it was given.
        var written = new java.util.HashMap<String, ObjectMetadata>();
        doAnswer(call -> {
            byte[] bytes = call.getArgument(1);
            written.put(call.<ObjectKey>getArgument(0).value(),
                    new ObjectMetadata(bytes.length, call.getArgument(2), sha256(bytes)));
            stored.put(call.<ObjectKey>getArgument(0).value(), bytes);
            return null;
        }).when(storage).write(any(), any(), any());
        when(storage.inspect(any())).thenAnswer(call -> written.get(call.<ObjectKey>getArgument(0).value()));
        // Reading an artifact back is what the thumbnail path does, so the double serves what it was given.
        when(storage.open(any())).thenAnswer(call -> {
            String key = call.<ObjectKey>getArgument(0).value();
            byte[] bytes = stored.get(key);
            if (bytes == null) throw new ObjectStorageException(ObjectStorageFailureCode.NOT_FOUND, false, null);
            return new io.memoryos.objectstorage.ObjectContent() {
                private final java.io.InputStream input = new java.io.ByteArrayInputStream(bytes);
                @Override public ObjectMetadata metadata() { return written.get(key); }
                @Override public java.io.InputStream inputStream() { return input; }
                @Override public void close() {}
            };
        });
        var tenants = TestDatabase.transactionalProxy(new JpaTenantAccessResolver(
                        new JpaTenantRepository(jpa.entityManager()), new IamLockRepository(jdbc)),
                TenantAccessResolver.class, jpa.transactionManager());
        var objects = new JdbcStoredObjectRepository(jdbc);
        writes = new DefaultObjectWriteService(objects, new JdbcObjectWriteRepository(jdbc), storage,
                new ObjectUploadProperties(Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(5),
                        Duration.ofMinutes(1), 16), jpa.transactionManager());
        artifacts = new JdbcInterpreterRepository(jdbc);
        pictures = new JdbcImageArtifactRepository(jdbc);
        var quotas = new StorageQuotaService(tenants,
                new LibraryStorageProperties(0), new JdbcLibraryRepository(jdbc));
        interpreter = new InterpreterService(artifacts, new InterpreterProperties(null, null),
                mock(IamAuthorization.class), tenants, writes, storage, quotas,
                // This suite covers the release itself, so deletion releases at once.
                new LibraryTrashProperties(java.time.Duration.ZERO),
                jpa.transactionManager(), io.memoryos.TestDatabase.noAudit());
        images = new ImageArtifactService(writes, storage, pictures, tenants, quotas,
                new LibraryTrashProperties(java.time.Duration.ZERO),
                jpa.transactionManager());
        cleanup = new ChatArtifactCleanupService(new JdbcChatArtifactCleanupRepository(jdbc),
                new DefaultStoredObjectRegistry(objects), writes, storage, jpa.transactionManager());
        seedConversation();
    }

    @AfterEach
    void close() {
        if (jpa != null) jpa.close();
        if (database != null) database.close();
    }

    @Test
    void deletingAGeneratedFileReleasesItsBytesPreviewAndMetadata() {
        var file = interpreter.store(tenant, messageId, "deck.pptx", PPTX, new byte[] {1, 2, 3});
        interpreter.storePreview(tenant, file, new byte[] {4, 5});
        var artifact = artifacts.ownedArtifact(tenant, owner, file).orElseThrow();
        var previewKey = artifact.previewKey();
        assertNotNull(previewKey);
        assertEquals(2, count("stored_objects"));

        interpreter.delete(owner, file);
        // Hidden at once, while the bytes are still there: the sweep owns their release.
        assertTrue(artifacts.ownedArtifact(tenant, owner, file).isEmpty());
        assertEquals(2, count("stored_objects"));

        assertEquals(1, cleanup.cleanup());
        verify(storage).delete(artifact.key());
        verify(storage).delete(previewKey);
        // The row stays as a tombstone for the answer, with nothing left pointing at bytes.
        assertEquals(1, count("chat_file_artifact WHERE purged_at IS NOT NULL AND object_key IS NULL"
                + " AND preview_object_key IS NULL AND stored_object_id IS NULL"));
        assertEquals(0, count("stored_objects"));
        assertEquals(0, count("object_writes"));
        // Nothing is left to claim, so a later sweep is a no-op.
        assertEquals(0, cleanup.cleanup());
    }

    @Test
    void aFailedDeletionKeepsTheClaimRetryableAndNeverLosesTheRow() {
        var image = images.store(tenant, messageId, new io.memoryos.chat.image.ImageProviderClient.Result(
                new byte[] {7, 7, 7}, "image/png", null));
        images.delete(owner, image);
        doThrow(new ObjectStorageException(ObjectStorageFailureCode.UNAVAILABLE, true, null))
                .when(storage).delete(any());

        assertEquals(0, cleanup.cleanup());
        assertEquals(1, count("chat_image_artifact"));
        assertEquals(1, count("stored_objects"));
        // The object is delete-pending and the claim is leased, so the row is not swept again at once.
        assertEquals("DELETE_PENDING", jdbc.sql("SELECT state FROM stored_objects").query(String.class).single());
        assertEquals(0, cleanup.cleanup());

        reset(storage);
        jdbc.sql("UPDATE chat_image_artifact SET cleanup_until = CURRENT_TIMESTAMP - INTERVAL '1' MINUTE").update();
        assertEquals(1, cleanup.cleanup());
        assertEquals(1, count("chat_image_artifact WHERE purged_at IS NOT NULL"));
        assertEquals(0, count("stored_objects"));
        assertEquals(0, count("object_writes"));
    }

    @Test
    void aPreviewConvertedAfterTheDeletionIsDiscardedRatherThanLeftAdopted() {
        var file = interpreter.store(tenant, messageId, "deck.pptx", PPTX, new byte[] {1, 2, 3});
        interpreter.delete(owner, file);

        interpreter.storePreview(tenant, file, new byte[] {4, 5});

        // The conversion found the file gone, so its object was never adopted: it is discarded, not orphaned.
        assertEquals(0, jdbc.sql("SELECT count(*) FROM chat_file_artifact WHERE id=:id AND preview_object_key IS NOT NULL")
                .param("id", file).query(Long.class).single());
        assertEquals(List.of("DISCARDED"), jdbc.sql("SELECT status FROM object_writes WHERE status <> 'ADOPTED'")
                .query(String.class).list());
        assertEquals(1, cleanup.cleanup());
        assertEquals(1, writes.cleanup(16));
        assertEquals(0, count("stored_objects"));
        assertEquals(0, count("object_writes"));
    }

    @Test
    void oneSweepClaimsBothTablesAndOnlyDeletedArtifacts() {
        var kept = interpreter.store(tenant, messageId, "kept.csv", "text/csv", new byte[] {1});
        var file = interpreter.store(tenant, messageId, "gone.csv", "text/csv", new byte[] {2});
        var image = images.store(tenant, messageId, new io.memoryos.chat.image.ImageProviderClient.Result(
                new byte[] {3}, "image/png", "a red shirt"));
        interpreter.delete(owner, file);
        images.delete(owner, image);

        assertEquals(2, cleanup.cleanup());
        assertEquals(List.of(kept), jdbc.sql("SELECT id FROM chat_file_artifact WHERE purged_at IS NULL")
                .query(UUID.class).list());
        assertEquals(1, count("chat_image_artifact WHERE purged_at IS NOT NULL"));
        assertEquals(1, count("stored_objects"));
    }

    @Test
    void theFirstThumbnailRequestWritesOneRenderingThatLaterReadsReuse() throws Exception {
        byte[] png = noisePng(1024, 768);
        var image = images.store(tenant, messageId, new io.memoryos.chat.image.ImageProviderClient.Result(
                png, "image/png", null));
        assertEquals(1, count("stored_objects"));

        byte[] thumbnail = read(image, ImageArtifactService.Variant.THUMBNAIL, "image/jpeg");
        assertTrue(thumbnail.length < png.length / 4, "thumbnail " + thumbnail.length + " of " + png.length);
        assertEquals(512, javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(thumbnail)).getWidth());
        assertEquals(2, count("stored_objects"));

        // A second read serves the rendering that was kept rather than making another one.
        assertArrayEquals(thumbnail, read(image, ImageArtifactService.Variant.THUMBNAIL, "image/jpeg"));
        assertEquals(2, count("stored_objects"));
        // The artifact itself is untouched: a preview still gets the full image.
        assertArrayEquals(png, read(image, ImageArtifactService.Variant.ORIGINAL, "image/png"));

        // Deleting the image releases both objects, so a thumbnail cannot outlive what it was made from.
        images.delete(owner, image);
        assertEquals(1, cleanup.cleanup());
        assertEquals(1, count("chat_image_artifact WHERE purged_at IS NOT NULL AND thumbnail_object_key IS NULL"));
        assertEquals(0, count("stored_objects"));
        assertEquals(0, count("object_writes"));
    }

    @Test
    void anImageWithNoThumbnailToMakeIsServedWhole() {
        byte[] notAnImage = new byte[ImageThumbnails.MIN_SOURCE_BYTES + 1];
        var image = images.store(tenant, messageId, new io.memoryos.chat.image.ImageProviderClient.Result(
                notAnImage, "image/webp", null));

        assertArrayEquals(notAnImage, read(image, ImageArtifactService.Variant.THUMBNAIL, "image/webp"));
        // Nothing decodable, so nothing was stored and the next request is free to try again.
        assertEquals(1, count("stored_objects"));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM chat_image_artifact WHERE thumbnail_object_key IS NOT NULL")
                .query(Long.class).single());
    }

    @Test
    void aPurgedArtifactKeepsItsCardOnTheAnswerAndLeavesTheLibrary() {
        var image = images.store(tenant, messageId, new io.memoryos.chat.image.ImageProviderClient.Result(
                new byte[] {9, 9}, "image/png", "a blue car"));
        images.delete(owner, image);
        assertEquals(1, cleanup.cleanup());

        // The answer still carries the image, marked deleted: that is the "the image was deleted" card.
        var onTheAnswer = images.forMessages(owner, List.of(messageId)).get(messageId);
        assertNotNull(onTheAnswer);
        assertEquals(1, onTheAnswer.size());
        assertEquals(image, onTheAnswer.getFirst().id());
        assertTrue(onTheAnswer.getFirst().deleted());
        // A turn's own context never offers it, because there is nothing left to edit.
        assertTrue(pictures.byMessages(tenant, List.of(messageId), false).isEmpty());

        // The trash no longer offers it, and nothing can bring it back: the bytes are gone.
        var trash = new JdbcLibraryRepository(jdbc).page(tenant, owner,
                new JdbcLibraryRepository.Filter("", java.util.Set.of(),
                        java.util.Set.of(), null, false, false, true, null),
                LibraryFile.Sort.DELETED, 0, 50);
        assertEquals(List.of(), trash.items());
        assertFalse(pictures.restore(tenant, owner, image));
    }

    private byte[] read(UUID image, ImageArtifactService.Variant variant, String expectedType) {
        try (var served = images.open(owner, image, variant)) {
            assertEquals(expectedType, served.mediaType());
            byte[] bytes = served.inputStream().readAllBytes();
            assertEquals(bytes.length, served.sizeBytes());
            return bytes;
        } catch (java.io.IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    /** Noise, so the PNG weighs what a generated image does. */
    private static byte[] noisePng(int width, int height) throws java.io.IOException {
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random = new java.util.Random(20260921);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, random.nextInt(0xFFFFFF));
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static ContentSha256 sha256(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return new ContentSha256(java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    /** Rows of a table, or of a table with a predicate: both read as "SELECT count(*) FROM <this>". */
    private long count(String from) {
        return jdbc.sql("SELECT count(*) FROM " + from).query(Long.class).single();
    }

    /** A conversation with one answer: artifacts take their owner and session from it. */
    private void seedConversation() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(jpa.transactionManager());
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'Artifacts','ACTIVE','test')")
                .param("id", tenant.value()).param("slug", tenant.value().toString()).update();
        owner = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", owner.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", owner.value()).update();
        var persona = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO persona(id,tenant_id,name,instructions,model,builtin_key)
                VALUES(:id,:tenant,'Default','','gpt','default')
                """).param("id", persona).param("tenant", tenant.value()).update();
        var session = UUID.randomUUID();
        var root = UUID.randomUUID();
        messageId = UUID.randomUUID();
        // chat_session and its root message reference each other; the deferred constraint needs one transaction.
        tx.executeWithoutResult(ignored -> {
            jdbc.sql("""
                    INSERT INTO chat_session(id,tenant_id,owner_actor_id,persona_id,root_message_id,title)
                    VALUES(:id,:tenant,:actor,:persona,:root,'Artifacts')
                    """).param("id", session).param("tenant", tenant.value()).param("actor", owner.value())
                    .param("persona", persona).param("root", root).update();
            jdbc.sql("""
                    INSERT INTO chat_message(id,session_id,role,status,finished_at)
                    VALUES(:id,:session,'ROOT','COMPLETED',CURRENT_TIMESTAMP)
                    """).param("id", root).param("session", session).update();
            jdbc.sql("""
                    INSERT INTO chat_message(id,session_id,parent_message_id,role,status,finished_at,deadline_at)
                    VALUES(:id,:session,:parent,'ASSISTANT','COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """).param("id", messageId).param("session", session).param("parent", root).update();
        });
    }
}
