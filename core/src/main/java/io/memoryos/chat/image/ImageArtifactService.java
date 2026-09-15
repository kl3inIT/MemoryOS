package io.memoryos.chat.image;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.persistence.JdbcImageArtifactRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
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
    /** Same ceiling as vision input; an edit source is read fully into memory. */
    private static final int EDIT_SOURCE_LIMIT = 20 * 1024 * 1024;
    private final ObjectWriteService writes;
    private final ObjectStorage storage;
    private final JdbcImageArtifactRepository artifacts;
    private final TenantAccessResolver tenants;
    private final TransactionTemplate tx;

    public ImageArtifactService(ObjectWriteService writes, ObjectStorage storage, JdbcImageArtifactRepository artifacts,
                                TenantAccessResolver tenants, PlatformTransactionManager transactionManager) {
        this.writes = writes; this.storage = storage; this.artifacts = artifacts; this.tenants = tenants;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record Served(ObjectContent content, String mediaType) {}
    public record Image(byte[] bytes, String mediaType) {}

    /** Opens the bytes of an owner-private generated image; the caller must close the returned content. */
    public Served open(ActorId actor, UUID id) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var found = artifacts.owned(tenant, actor, id).orElseThrow(ChatException::unavailable);
        var content = storage.open(found.key());
        try {
            if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                    || artifacts.owned(tenant, actor, id).isEmpty()) throw ChatException.unavailable();
            return new Served(content, found.mediaType());
        } catch (RuntimeException failed) {
            content.close();
            throw failed;
        }
    }

    /**
     * Images for an already-authorized page of messages, keyed by message id. The caller has resolved these
     * message ids from an ownership-checked history read; results are scoped to the actor's active Tenant.
     */
    public Map<UUID, List<JdbcImageArtifactRepository.Artifact>> forMessages(ActorId actor, Collection<UUID> messageIds) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        return artifacts.byMessages(tenant, messageIds);
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
        UUID id = UUID.randomUUID();
        var staged = writes.stage(tenant, new ObjectWriteService.Specification(
                "image-" + id + extension(result.mediaType()), result.mediaType(), false), result.bytes());
        boolean adopted = false;
        try {
            tx.executeWithoutResult(ignored -> {
                writes.adopt(tenant, staged);
                artifacts.insert(tenant, messageId, id, staged.object().id().value(), staged.object().key(),
                        result.mediaType(), result.revisedPrompt(), sourceArtifactId, sourceFileId);
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
