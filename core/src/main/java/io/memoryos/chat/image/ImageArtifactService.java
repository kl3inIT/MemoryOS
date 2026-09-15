package io.memoryos.chat.image;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.persistence.JdbcImageArtifactRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectWriteService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Server-side storage of a generated image: stage bytes, then adopt and record within one transaction. */
@Service
public class ImageArtifactService {
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

    /** Persists a generated image against the assistant message; returns the artifact id. */
    public UUID store(TenantId tenant, UUID messageId, ImageProviderClient.Result result) {
        UUID id = UUID.randomUUID();
        var staged = writes.stage(tenant, new ObjectWriteService.Specification(
                "image-" + id + extension(result.mediaType()), result.mediaType(), false), result.bytes());
        boolean adopted = false;
        try {
            tx.executeWithoutResult(ignored -> {
                writes.adopt(tenant, staged);
                artifacts.insert(tenant, messageId, id, staged.object().id().value(), staged.object().key(),
                        result.mediaType(), result.revisedPrompt());
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
