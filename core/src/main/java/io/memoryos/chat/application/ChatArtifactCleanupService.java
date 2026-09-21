package io.memoryos.chat.application;

import io.memoryos.chat.persistence.JdbcChatArtifactCleanupRepository;
import io.memoryos.chat.persistence.JdbcChatArtifactCleanupRepository.Claim;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectWriteService;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Releases the bytes of Chat artifacts deleted from the file library (MEM-142). Artifact bytes are adopted
 * object writes, which the generic reapers never select, so the owning capability deletes them: mark the
 * objects delete-pending, delete the keys outside any transaction, then release ownership and the rows under
 * the claim this worker holds. A failure leaves the claim to lapse and the artifact to be swept again.
 */
@Service
public class ChatArtifactCleanupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatArtifactCleanupService.class);
    private static final int BATCH = 20;

    private final JdbcChatArtifactCleanupRepository claims;
    private final StoredObjectRegistry storedObjects;
    private final ObjectWriteService writes;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;

    public ChatArtifactCleanupService(JdbcChatArtifactCleanupRepository claims, StoredObjectRegistry storedObjects,
                                      ObjectWriteService writes, ObjectStorage storage,
                                      PlatformTransactionManager transactionManager) {
        this.claims = claims; this.storedObjects = storedObjects; this.writes = writes; this.storage = storage;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns how many artifacts were fully released. */
    public int cleanup() {
        int released = 0;
        var claimed = java.util.Objects.requireNonNull(tx.execute(ignored -> claims.claim(BATCH)));
        for (Claim claim : claimed) {
            try {
                tx.executeWithoutResult(ignored -> {
                    storedObjects.markDeletePending(claim.tenantId(), claim.object());
                    if (claim.previewObject() != null) storedObjects.markDeletePending(claim.tenantId(), claim.previewObject());
                });
                storage.delete(claim.key());
                if (claim.previewKey() != null) storage.delete(claim.previewKey());
                // One transaction, so a lapsed claim rolls the release back and leaves the artifact to its new owner.
                tx.executeWithoutResult(ignored -> {
                    release(claim, claim.object());
                    release(claim, claim.previewObject());
                    if (!claims.remove(claim)) throw new IllegalStateException("cleanup claim lapsed");
                });
                released++;
            } catch (RuntimeException failed) {
                LOGGER.atWarn().addKeyValue("event", "chat.artifact.cleanup.retry")
                        .addKeyValue("artifact_kind", claim.kind().name())
                        .addKeyValue("error_type", failed.getClass().getName())
                        .log("Chat artifact cleanup failed; the claim lapses and the sweep retries");
            }
        }
        if (released > 0) {
            LOGGER.atInfo().addKeyValue("event", "chat.artifact.cleanup.released")
                    .addKeyValue("released", released).log("Released the bytes of deleted Chat artifacts");
        }
        return released;
    }

    private void release(Claim claim, @Nullable StoredObjectId object) {
        if (object == null) return;
        writes.releaseAdopted(claim.tenantId(), object);
        storedObjects.remove(claim.tenantId(), object);
    }
}
