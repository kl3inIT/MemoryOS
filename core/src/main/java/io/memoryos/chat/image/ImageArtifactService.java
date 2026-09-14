package io.memoryos.chat.image;

import io.memoryos.chat.persistence.JdbcImageArtifactRepository;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ObjectWriteService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Server-side storage of a generated image: stage bytes, then adopt and record within one transaction. */
@Service
public class ImageArtifactService {
    private final ObjectWriteService writes;
    private final JdbcImageArtifactRepository artifacts;
    private final TransactionTemplate tx;

    public ImageArtifactService(ObjectWriteService writes, JdbcImageArtifactRepository artifacts,
                                PlatformTransactionManager transactionManager) {
        this.writes = writes; this.artifacts = artifacts; this.tx = new TransactionTemplate(transactionManager);
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
