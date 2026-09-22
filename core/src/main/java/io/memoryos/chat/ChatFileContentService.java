package io.memoryos.chat;

import io.memoryos.chat.image.ImageThumbnails;
import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectWriteService;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatFileContentService {
    /** What an upload has to be for a thumbnail to exist: the types the renderer and the browser both take. */
    private static final Set<String> IMAGE_MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    /** A thumbnail source is read fully into memory, the ceiling generated images use; past it the original is served unshrunk. */
    private static final int THUMBNAIL_SOURCE_LIMIT = 20 * 1024 * 1024;

    private final JdbcUserFileRepository files;
    private final TenantAccessResolver tenants;
    private final ObjectStorage storage;
    private final ObjectWriteService writes;
    private final TransactionTemplate tx;
    public ChatFileContentService(JdbcUserFileRepository files, TenantAccessResolver tenants, ObjectStorage storage,
                                  ObjectWriteService writes, PlatformTransactionManager transactionManager) {
        this.files = files; this.tenants = tenants; this.storage = storage; this.writes = writes;
        this.tx = new TransactionTemplate(transactionManager);
    }

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

    public ObjectContent open(ActorId actor, TenantId tenant, UUID id) {
        if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()) throw ChatException.unavailable();
        var found = files.raw(tenant, actor, id).orElseThrow(ChatException::unavailable);
        var content = storage.open(found.reference().key());
        try {
            if (!content.metadata().equals(found.reference().metadata())
                    || tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                    || files.raw(tenant, actor, id).map(JdbcUserFileRepository.Servable::reference)
                            .filter(found.reference()::equals).isEmpty())
                throw ChatException.unavailable();
            return content;
        } catch (RuntimeException failed) {
            content.close();
            throw failed;
        }
    }

    /**
     * The small rendering the file library shows in place of an uploaded image. One is written the first time
     * it is asked for and reused afterwards; an image this build cannot decode has none, and the original is
     * served instead, which is correct and only larger. The caller must close the result.
     */
    public Served thumbnail(ActorId actor, UUID id) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var found = files.raw(tenant, actor, id).orElseThrow(ChatException::unavailable);
        if (!IMAGE_MEDIA_TYPES.contains(found.mediaType()))
            throw ChatException.invalid("Only image files have a thumbnail");
        if (found.thumbnailKey() != null && found.thumbnailMediaType() != null)
            return stored(actor, tenant, id, found.thumbnailKey(), found.thumbnailMediaType());
        var rendered = render(tenant, id, found);
        if (rendered.isPresent()) return Served.of(rendered.get());
        return stored(actor, tenant, id, found.reference().key(), found.mediaType());
    }

    /** Opens a stored object and re-checks the read after opening, so a revoked membership cannot be served. */
    private Served stored(ActorId actor, TenantId tenant, UUID id, ObjectKey key, String mediaType) {
        var content = storage.open(key);
        try {
            if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                    || files.raw(tenant, actor, id).isEmpty()) throw ChatException.unavailable();
            return Served.of(content, mediaType);
        } catch (RuntimeException failed) {
            content.close();
            throw failed;
        }
    }

    /**
     * Renders the library thumbnail of an already-authorized upload and keeps it for later reads. A thumbnail
     * is derived from bytes the owner is already charged for, so it does not take from their storage limit.
     * Whoever loses the race to record one still serves what they rendered and releases the object they staged.
     */
    private Optional<ImageThumbnails.Rendered> render(TenantId tenant, UUID id, JdbcUserFileRepository.Servable found) {
        byte[] original;
        try (var content = storage.open(found.reference().key())) {
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
                if (files.attachThumbnail(tenant, id, staged.object().id().value(), staged.object().key(),
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

    private static String extension(String mediaType) {
        return switch (mediaType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }

    /** READY attachments among {@code ids} that the owner can read now; unknown or unreadable ids are skipped. */
    public java.util.List<UserFile> readable(ActorId actor, TenantId tenant, java.util.Collection<UUID> ids) {
        if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()) throw ChatException.unavailable();
        return files.owned(tenant, actor, java.util.Set.copyOf(ids)).stream()
                .map(JdbcUserFileRepository.Row::file).toList();
    }
    public ObjectContent open(ActorId actor, UUID id) {
        return open(actor, tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable), id);
    }
    /** Onyx {@code fetch_chat_file(parsed=true)} for an attachment: an owner-private xlsx as CSV text per sheet. */
    public java.util.List<io.memoryos.chat.interpreter.SpreadsheetPreview.Sheet> spreadsheet(ActorId actor, UUID id) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var file = files.owned(tenant, actor, id, false).orElseThrow(ChatException::unavailable).file();
        if (!"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(file.mediaType()))
            throw ChatException.invalid("Only xlsx files have a spreadsheet preview");
        try (var input = open(actor, tenant, id)) {
            return io.memoryos.chat.interpreter.SpreadsheetPreview.parse(input.inputStream());
        } catch (java.io.IOException failed) {
            throw ChatException.invalid("The workbook cannot be previewed");
        }
    }

    public byte[] image(ActorId actor, TenantId tenant, UUID id) {
        var file = files.readable(tenant, actor, id, false).orElseThrow(ChatException::unavailable).file();
        if (!IMAGE_MEDIA_TYPES.contains(file.mediaType()) || file.sizeBytes() > 20971520)
            throw ChatException.invalid("Image is unavailable or exceeds the vision limit.");
        try (var input = open(actor, tenant, id)) {
            var bytes = input.inputStream().readNBytes(Math.toIntExact(file.sizeBytes()) + 1);
            if (bytes.length != file.sizeBytes()) throw ChatException.unavailable();
            if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                    || files.readable(tenant, actor, id, false).filter(row -> row.file().status() == UserFile.Status.READY).isEmpty())
                throw ChatException.unavailable();
            return bytes;
        } catch (java.io.IOException failed) { throw ChatException.unavailable(); }
    }
}
