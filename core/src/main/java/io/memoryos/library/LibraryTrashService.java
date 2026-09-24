package io.memoryos.library;

import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
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
@EnableConfigurationProperties(LibraryTrashProperties.class)
public class LibraryTrashService {
    /** Emptying the trash acts on a bounded batch, so one request cannot queue unbounded work. */
    public static final int EMPTY_LIMIT = 500;

    private final TenantAccessResolver tenants;
    private final JdbcUserFileRepository files;
    private final LibraryArtifacts artifacts;
    private final LibraryTrashProperties trash;
    private final TransactionTemplate tx;

    public LibraryTrashService(TenantAccessResolver tenants, JdbcUserFileRepository files, LibraryArtifacts artifacts,
                                   LibraryTrashProperties trash, PlatformTransactionManager transactionManager) {
        this.tenants = tenants; this.files = files; this.artifacts = artifacts; this.trash = trash;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** How long a deleted file stays restorable; zero means deletion releases the bytes at once. */
    public Duration window() { return trash.trashAfter(); }

    public void restore(ActorId actor, LibraryFile.Source source, UUID id) {
        boolean restored = Boolean.TRUE.equals(tx.execute(ignored -> {
            var tenant = write(actor);
            return switch (source) {
                case UPLOAD, MEETING -> files.restore(tenant, actor, id);
                case GENERATED, IMAGE -> artifacts.restore(tenant, actor, source, id);
            };
        }));
        if (!restored) throw LibraryException.unavailable();
    }

    /** Ends the window now: the bytes are released by the same routes that release them when it lapses. */
    public void purge(ActorId actor, LibraryFile.Source source, UUID id) {
        boolean purged = Boolean.TRUE.equals(tx.execute(ignored -> {
            var tenant = write(actor);
            return switch (source) {
                case UPLOAD, MEETING -> files.purgeNow(tenant, actor, id);
                case GENERATED, IMAGE -> artifacts.purgeNow(tenant, actor, source, id);
            };
        }));
        if (!purged) throw LibraryException.unavailable();
    }

    /** Ends the window for everything the owner has in the trash; answers how many files that was. */
    public int empty(ActorId actor) {
        return java.util.Objects.requireNonNull(tx.execute(ignored -> {
            var tenant = write(actor);
            int purged = 0;
            for (var upload : files.trashed(tenant, actor, EMPTY_LIMIT)) {
                if (files.purgeNow(tenant, actor, upload)) purged++;
            }
            purged += artifacts.purgeTrashed(tenant, actor, EMPTY_LIMIT);
            return purged;
        }));
    }

    private TenantId write(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(LibraryException::unavailable).tenantId();
        files.lockOwner(tenant, actor);
        return tenant;
    }
}
