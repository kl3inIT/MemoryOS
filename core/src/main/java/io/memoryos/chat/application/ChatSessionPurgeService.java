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
 */
@Service
@EnableConfigurationProperties(ChatRetentionProperties.class)
public class ChatSessionPurgeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatSessionPurgeService.class);

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
            for (var session : sessions.claim(retention.batchSize())) {
                sessions.releaseArtifacts(session);
                sessions.purge(session);
                count++;
            }
            return count;
        }));
        if (purged > 0) {
            LOGGER.atInfo().addKeyValue("event", "chat.session.purged").addKeyValue("purged", purged)
                    .log("Removed conversations their owner had deleted");
        }
        return purged;
    }
}
