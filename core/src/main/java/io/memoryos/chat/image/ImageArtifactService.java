package io.memoryos.chat.image;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.persistence.JdbcImageArtifactRepository;
import io.memoryos.library.ChatStorageQuotaService;
import io.memoryos.library.ImageThumbnails;
import io.memoryos.library.LibraryTrashProperties;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectWriteService;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Server-side storage of a generated image: stage bytes, then adopt and record within one transaction. */
@Service
public class ImageArtifactService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ImageArtifactService.class);
    /** Same ceiling as vision input; an edit source is read fully into memory. */
    private static final int EDIT_SOURCE_LIMIT = 20 * 1024 * 1024;
    /** A thumbnail source is read fully into memory as well; past this the original is served unshrunk. */
    private static final int THUMBNAIL_SOURCE_LIMIT = EDIT_SOURCE_LIMIT;
    private final ObjectWriteService writes;
    private final ObjectStorage storage;
    private final JdbcImageArtifactRepository artifacts;
    private final TenantAccessResolver tenants;
    private final TransactionTemplate tx;

    private final ChatStorageQuotaService quotas;
    private final LibraryTrashProperties trash;

    public ImageArtifactService(ObjectWriteService writes, ObjectStorage storage, JdbcImageArtifactRepository artifacts,
                                TenantAccessResolver tenants, ChatStorageQuotaService quotas,
                                LibraryTrashProperties trash,
                                PlatformTransactionManager transactionManager) {
        this.writes = writes; this.storage = storage; this.artifacts = artifacts; this.tenants = tenants;
        this.quotas = quotas; this.trash = trash; this.tx = new TransactionTemplate(transactionManager);
    }

    /** Which rendering of an image a caller wants: the artifact itself, or the library's small one. */
    public enum Variant { ORIGINAL, THUMBNAIL }

    /**
     * Bytes to serve, either streamed from storage or held in memory when a thumbnail was rendered for this
     * request. The caller must close it.
     */
    public record Served(String mediaType, long sizeBytes, @Nullable ObjectContent content, byte @Nullable [] bytes)
            implements AutoCloseable {
        static Served of(ObjectContent content, String mediaType) {
            return new Served(mediaType, content.metadata().sizeBytes(), content, null);
        }

        static Served of(ImageThumbnails.Rendered rendered) {
            return new Served(rendered.mediaType(), rendered.bytes().length, null, rendered.bytes());
        }

        public java.io.InputStream inputStream() {
            return content != null ? content.inputStream() : new java.io.ByteArrayInputStream(requireBytes());
        }

        private byte[] requireBytes() {
            if (bytes == null) throw new IllegalStateException("served image has neither content nor bytes");
            return bytes;
        }

        @Override
        public void close() {
            if (content != null) content.close();
        }
    }

    public record Image(byte[] bytes, String mediaType) {}

    /** Opens the bytes of an owner-private generated image; the caller must close the returned content. */
    public Served open(ActorId actor, UUID id) {
        return open(actor, id, Variant.ORIGINAL);
    }

    /**
     * Opens an owner-private generated image in the requested rendering. A thumbnail is written the first time
     * one is asked for and reused afterwards; an image this build cannot decode has none, and the original is
     * served instead, which is correct and only larger.
     */
    public Served open(ActorId actor, UUID id, Variant variant) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var found = artifacts.owned(tenant, actor, id).orElseThrow(ChatException::unavailable);
        if (variant == Variant.THUMBNAIL) {
            if (found.thumbnailKey() != null && found.thumbnailMediaType() != null)
                return stored(actor, tenant, id, found.thumbnailKey(), found.thumbnailMediaType());
            var rendered = thumbnail(tenant, id, found);
            if (rendered.isPresent()) return Served.of(rendered.get());
        }
        return stored(actor, tenant, id, found.key(), found.mediaType());
    }

    /** Opens a stored object and re-checks the read after opening, so a revoked membership cannot be served. */
    private Served stored(ActorId actor, TenantId tenant, UUID id, io.memoryos.objectstorage.ObjectKey key, String mediaType) {
        var content = storage.open(key);
        try {
            if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                    || artifacts.owned(tenant, actor, id).isEmpty()) throw ChatException.unavailable();
            return Served.of(content, mediaType);
        } catch (RuntimeException failed) {
            content.close();
            throw failed;
        }
    }

    /**
     * Renders the library thumbnail of an already-authorized artifact and keeps it for later reads. A thumbnail
     * is derived from bytes the owner is already charged for, so it does not take from their storage limit.
     * Whoever loses the race to record one still serves what they rendered and releases the object they staged.
     */
    private Optional<ImageThumbnails.Rendered> thumbnail(TenantId tenant, UUID id, JdbcImageArtifactRepository.Servable found) {
        byte[] original;
        try (var content = storage.open(found.key())) {
            original = content.inputStream().readNBytes(THUMBNAIL_SOURCE_LIMIT + 1);
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
        if (original.length > THUMBNAIL_SOURCE_LIMIT) return Optional.empty();
        var rendered = ImageThumbnails.render(original);
        if (rendered.isEmpty()) return rendered;
        var staged = writes.stage(tenant, new ObjectWriteService.Specification(
                "thumb-" + id + extension(rendered.get().mediaType()), rendered.get().mediaType(), false),
                rendered.get().bytes());
        boolean adopted = false;
        try {
            adopted = Boolean.TRUE.equals(tx.execute(ignored -> {
                writes.adopt(tenant, staged);
                if (artifacts.attachThumbnail(tenant, id, staged.object().id().value(), staged.object().key(),
                        rendered.get().mediaType())) return true;
                throw new ThumbnailAlreadyRecorded();
            }));
        } catch (RuntimeException ignored) {
            // Lost the race, or the write failed: the request still serves the rendering it made.
        } finally {
            if (!adopted) writes.discard(tenant, staged); // Never leave an unreferenced adopted object.
        }
        return rendered;
    }

    /** Rolls the adopt back when another request recorded a thumbnail first. */
    private static final class ThumbnailAlreadyRecorded extends RuntimeException {
        ThumbnailAlreadyRecorded() { super(null, null, false, false); }
    }

    /**
     * Images for an already-authorized page of messages, keyed by message id. The caller has resolved these
     * message ids from an ownership-checked history read; results are scoped to the actor's active Tenant.
     */
    public Map<UUID, List<GeneratedImage>> forMessages(ActorId actor, Collection<UUID> messageIds) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        return artifacts.byMessages(tenant, messageIds, true);
    }

    /**
     * Hides a generated image the caller owns and leaves its bytes to the cleanup sweep. Deleting an image
     * already deleted succeeds, so a repeated request from the library is not an error.
     */
    public void delete(ActorId actor, UUID id) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        tx.executeWithoutResult(ignored -> {
            if (!artifacts.markDeleted(tenant, actor, id, trash.trashAfter())) throw ChatException.unavailable();
        });
        LOGGER.atInfo().addKeyValue("event", "chat.artifact.deleted").addKeyValue("artifact_kind", "IMAGE")
                .log("Generated image hidden; the cleanup sweep releases its bytes");
    }

    /**
     * Bytes of an image generated earlier in the owner's session, as an edit source. Empty when the id is not
     * such an image, so the caller can try an attached file instead.
     */
    public Optional<Image> sessionImage(ActorId actor, TenantId tenant, UUID session, UUID id) {
        if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()) throw ChatException.unavailable();
        var found = artifacts.inSession(tenant, actor, session, id);
        if (found.isEmpty()) return Optional.empty();
        try (var content = storage.open(found.get().key())) {
            byte[] bytes = content.inputStream().readNBytes(EDIT_SOURCE_LIMIT + 1);
            if (bytes.length > EDIT_SOURCE_LIMIT) throw ChatException.invalid("Image exceeds the edit limit.");
            if (artifacts.inSession(tenant, actor, session, id).isEmpty()) throw ChatException.unavailable();
            return Optional.of(new Image(bytes, found.get().mediaType()));
        } catch (IOException failed) {
            throw ChatException.unavailable();
        }
    }

    /** Persists a generated image against the assistant message; returns the artifact id. */
    public UUID store(TenantId tenant, UUID messageId, ImageProviderClient.Result result) {
        return store(tenant, messageId, result, null, null);
    }

    /** Persists an image; an edit records the generated image or attached file it was made from. */
    public UUID store(TenantId tenant, UUID messageId, ImageProviderClient.Result result,
                      @Nullable UUID sourceArtifactId, @Nullable UUID sourceFileId) {
        // The image belongs to the owner of the conversation, so it is their storage limit that applies.
        var owner = artifacts.owner(tenant, messageId).map(ActorId::new)
                .orElseThrow(() -> new IllegalStateException("generated image has no answer in this tenant"));
        quotas.requireRoom(tenant, owner, result.bytes().length);
        UUID id = UUID.randomUUID();
        var staged = writes.stage(tenant, new ObjectWriteService.Specification(
                "image-" + id + extension(result.mediaType()), result.mediaType(), false), result.bytes());
        boolean adopted = false;
        try {
            tx.executeWithoutResult(ignored -> {
                writes.adopt(tenant, staged);
                artifacts.insert(tenant, messageId, id, staged.object().id().value(), staged.object().key(),
                        result.mediaType(), extension(result.mediaType()), result.bytes().length,
                        result.revisedPrompt(), sourceArtifactId, sourceFileId);
            });
            adopted = true;
            return id;
        } finally {
            if (!adopted) writes.discard(tenant, staged); // Never leave an unreferenced adopted object.
        }
    }

    private static String extension(String mediaType) {
        return switch (mediaType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }
}
