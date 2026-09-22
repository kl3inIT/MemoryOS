package io.memoryos.chat.application;

import io.memoryos.chat.persistence.JdbcChatSessionPurgeRepository;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes conversations their owner deleted, when the deployment asks for it (MEM-143). Deletion itself stays
 * soft and immediate; this sweep is what makes it final, and it also drains conversations deleted before the
 * switch was turned on. The bytes of the generated files go through the MEM-142 artifact sweep, which owns the
 * object-storage release; here they are only marked.
 *
 * <p>MEM-153 adds the waiting window before a purge, the conversations that delete themselves because they were
 * temporary, and the Tenant policy that deletes conversations nobody has touched for long enough. All three end
 * in the same purge, so a conversation is removed one way however it came to be deleted.
 */
@Service
@EnableConfigurationProperties(ChatRetentionProperties.class)
public class ChatSessionPurgeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatSessionPurgeService.class);
    /** Conversations one policy run deletes per Tenant, so a first run on a large Tenant is spread out. */
    private static final int POLICY_BATCH = 200;
    /** Temporary conversations expired per run; there are never many, because each one is short-lived. */
    private static final int TEMPORARY_BATCH = 100;

    private final JdbcChatSessionPurgeRepository sessions;
    private final ChatRetentionProperties retention;
    private final TransactionTemplate tx;

    public ChatSessionPurgeService(JdbcChatSessionPurgeRepository sessions, ChatRetentionProperties retention,
                                   PlatformTransactionManager transactionManager) {
        this.sessions = sessions; this.retention = retention;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns how many conversations were purged; zero while the deployment keeps deletions soft. */
    public int purge() {
        if (!retention.hardDelete()) return 0;
        int purged = Objects.requireNonNull(tx.execute(ignored -> {
            int count = 0;
            for (var session : sessions.claim(retention.batchSize(), retention.deletedAfter())) {
                sessions.releaseArtifacts(session);
                // Only a temporary conversation owns uploads; for any other this releases nothing.
                sessions.releaseTemporaryUploads(session);
                sessions.purge(session);
                count++;
            }
            return count;
        }));
        if (purged > 0) {
            LOGGER.atInfo().addKeyValue("event", "chat.session.purged").addKeyValue("purged", purged)
                    .addKeyValue("awaiting", sessions.awaiting(retention.deletedAfter()))
                    .log("Removed conversations their owner had deleted");
        }
        return purged;
    }

    /**
     * Deletes temporary conversations whose last message is older than the window. This needs no deployment
     * switch: a temporary conversation was promised to leave no history, and the purge above then removes it
     * and its uploads whether or not hard deletion is on.
     */
    public int expireTemporary() {
        if (retention.temporaryAfter().isZero()) return 0;
        int expired = Objects.requireNonNull(tx.execute(
                ignored -> sessions.expireTemporary(retention.temporaryAfter(), TEMPORARY_BATCH)));
        if (expired > 0) {
            LOGGER.atInfo().addKeyValue("event", "chat.session.temporary.expired").addKeyValue("expired", expired)
                    .log("Deleted temporary conversations past their window");
        }
        return expired;
    }

    /**
     * Applies the retention each person chose for their own conversations: one nobody has touched for longer
     * than that number of days is deleted exactly as its owner would have deleted it, and the purge then
     * treats it like any other deletion. The log carries counts only, never a title or a message.
     */
    public int applyRetentionPolicies() {
        int deleted = 0;
        for (var policy : sessions.policies()) {
            int forOwner = Objects.requireNonNull(tx.execute(ignored ->
                    sessions.applyRetention(policy.tenant(), policy.owner(), policy.days(), POLICY_BATCH)));
            if (forOwner > 0) {
                LOGGER.atInfo().addKeyValue("event", "chat.retention.applied")
                        .addKeyValue("tenant_id", policy.tenant()).addKeyValue("days", policy.days())
                        .addKeyValue("deleted", forOwner)
                        .log("Deleted conversations past the owner's retention policy");
            }
            deleted += forOwner;
        }
        return deleted;
    }
}
