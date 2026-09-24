package io.memoryos.chat;

import io.memoryos.chat.application.ChatRetentionProperties;
import io.memoryos.chat.interpreter.persistence.JdbcInterpreterRepository;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcImageArtifactRepository;
import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The file library's trash (MEM-152, [ADR 0014](../../../../../../docs/decisions/0014-file-library-trash.md)).
 * Deleting a file already hid it from every listing and route; what this adds is the window before its bytes are
 * released, during which its owner may take it back or end it early. Restoring is only ever possible while the
 * bytes are still there: for an upload, while the byte-releasing work has not been queued, and for an artifact,
 * while the sweep has not claimed it.
 */
@Service
@EnableConfigurationProperties(ChatRetentionProperties.class)
public class ChatLibraryTrashService {
    /** Emptying the trash acts on a bounded batch, so one request cannot queue unbounded work. */
    public static final int EMPTY_LIMIT = 500;

    private final TenantAccessResolver tenants;
    private final JdbcChatRepository chats;
    private final JdbcUserFileRepository files;
    private final JdbcInterpreterRepository generated;
    private final JdbcImageArtifactRepository images;
    private final ChatRetentionProperties retention;
    private final TransactionTemplate tx;

    public ChatLibraryTrashService(TenantAccessResolver tenants, JdbcChatRepository chats, JdbcUserFileRepository files,
                                   JdbcInterpreterRepository generated, JdbcImageArtifactRepository images,
                                   ChatRetentionProperties retention, PlatformTransactionManager transactionManager) {
        this.tenants = tenants; this.chats = chats; this.files = files; this.generated = generated;
        this.images = images; this.retention = retention; this.tx = new TransactionTemplate(transactionManager);
    }

    /** How long a deleted file stays restorable; zero means deletion releases the bytes at once. */
    public Duration window() { return retention.trashAfter(); }

    public void restore(ActorId actor, ChatLibraryFile.Source source, UUID id) {
        boolean restored = Boolean.TRUE.equals(tx.execute(ignored -> {
            var tenant = write(actor);
            return switch (source) {
                case UPLOAD, MEETING -> files.restore(tenant, actor, id);
                case GENERATED -> generated.restoreArtifact(tenant, actor, id);
                case IMAGE -> images.restore(tenant, actor, id);
            };
        }));
        if (!restored) throw ChatException.unavailable();
    }

    /** Ends the window now: the bytes are released by the same routes that release them when it lapses. */
    public void purge(ActorId actor, ChatLibraryFile.Source source, UUID id) {
        boolean purged = Boolean.TRUE.equals(tx.execute(ignored -> {
            var tenant = write(actor);
            return switch (source) {
                case UPLOAD, MEETING -> files.purgeNow(tenant, actor, id);
                case GENERATED -> generated.purgeArtifactNow(tenant, actor, id);
                case IMAGE -> images.purgeNow(tenant, actor, id);
            };
        }));
        if (!purged) throw ChatException.unavailable();
    }

    /** Ends the window for everything the owner has in the trash; answers how many files that was. */
    public int empty(ActorId actor) {
        return java.util.Objects.requireNonNull(tx.execute(ignored -> {
            var tenant = write(actor);
            int purged = 0;
            for (var upload : files.trashed(tenant, actor, EMPTY_LIMIT)) {
                if (files.purgeNow(tenant, actor, upload)) purged++;
            }
            purged += generated.purgeTrashedArtifacts(tenant, actor, EMPTY_LIMIT);
            purged += images.purgeTrashed(tenant, actor, EMPTY_LIMIT);
            return purged;
        }));
    }

    private TenantId write(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        return tenant;
    }
}
