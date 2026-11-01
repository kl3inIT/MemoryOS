package io.memoryos.connector.lark;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.LarkProviderException.Failure;
import io.memoryos.connector.lark.persistence.JdbcLarkCredentialRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkDirectoryRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkDirectoryRepository.Kind;
import io.memoryos.connector.lark.persistence.JdbcLarkDirectoryRepository.Run;
import io.memoryos.connector.lark.persistence.JdbcLarkDirectoryRepository.Task;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads who the chats, departments and spaces named by a credential's SYNC Sources contain, one Lark page at a
 * time, so a sync step never exceeds its budget. A page is read outside any transaction and applied under the
 * credential row lock only while the run still stands, so Sources sharing the credential never apply a page twice.
 * A department counts the people of every department below it, and a space the people of the chats and departments
 * that are its members.
 */
@Service
public class LarkDirectorySynchronizer {
    /** A newly named chat or person is asked about this soon after the previous run, not at the next interval. */
    private static final Duration SETTLE = Duration.ofMinutes(1);
    private final JdbcLarkDirectoryRepository directory;
    private final JdbcLarkCredentialRepository credentials;
    private final TransactionTemplate transactions;
    private final Duration interval;

    public LarkDirectorySynchronizer(JdbcLarkDirectoryRepository directory, JdbcLarkCredentialRepository credentials,
            PlatformTransactionManager transactionManager,
            @Value("${memoryos.lark.directory-sync-interval:PT1H}") Duration interval) {
        if (interval.isNegative() || interval.isZero())
            throw new IllegalArgumentException("directory sync interval must be positive");
        this.directory = directory;
        this.credentials = credentials;
        this.transactions = new TransactionTemplate(transactionManager);
        this.interval = interval;
    }

    /**
     * Advances the credential's directory by at most one Lark page. Returns {@code false} when nothing is due, so
     * the caller can stop asking during this execution.
     */
    public boolean advance(TenantId tenant, CredentialId credential, long credentialRevision,
            LarkGateway.Session session) {
        var run = transactions.execute(_ -> {
            if (!current(tenant, credential, credentialRevision)) return null;
            return directory.running(tenant, credential)
                    .or(() -> directory.due(tenant, credential, interval, SETTLE)
                            ? Optional.of(directory.start(tenant, credential)) : Optional.empty())
                    .orElse(null);
        });
        if (run == null) return false;
        try {
            var task = transactions.execute(_ -> directory.nextTask(run).orElse(null));
            if (task != null) {
                list(run, task, credentialRevision, session);
                return true;
            }
            var pending = transactions.execute(_ -> directory.pendingUsers(run, LarkGateway.USER_BATCH));
            if (pending != null && !pending.isEmpty()) {
                var users = session.users(pending);
                apply(run, credentialRevision, () -> directory.recordUsers(run, pending, users));
                return true;
            }
            apply(run, credentialRevision, () -> directory.complete(run));
            return true;
        } catch (LarkProviderException exception) {
            apply(run, credentialRevision, () -> directory.fail(run, "SOURCE_LARK_" + exception.failure().name()));
            return false;
        }
    }

    private void list(Run run, Task task, long credentialRevision, LarkGateway.Session session) {
        try {
            if (task.kind() == Kind.DEPARTMENT && !task.childrenListed()) {
                var page = session.departmentChildren(task.groupId(), task.pageToken());
                apply(run, credentialRevision,
                        () -> directory.recordChildren(run, task, page.departmentIds(), page.pageToken()));
            } else if (task.kind() == Kind.SPACE) {
                var page = session.spaceMembers(task.groupId(), task.pageToken());
                apply(run, credentialRevision,
                        () -> directory.recordSpaceMembers(run, task, page.members(), page.pageToken()));
            } else {
                var page = task.kind() == Kind.CHAT ? session.chatMembers(task.groupId(), task.pageToken())
                        : session.departmentMembers(task.groupId(), task.pageToken());
                apply(run, credentialRevision,
                        () -> directory.recordMembers(run, task, page.openIds(), page.pageToken()));
            }
        } catch (LarkProviderException exception) {
            // The connection itself failing ends the run; the active generation stays in force.
            if (exception.failure() == Failure.AUTHENTICATION || exception.failure() == Failure.QUOTA
                    || exception.failure() == Failure.UNAVAILABLE) throw exception;
            // A chat the bot is not in, a department outside the app's contact range, a group since deleted:
            // nobody is admitted through it.
            apply(run, credentialRevision, () -> directory.skip(run, task));
        }
    }

    private void apply(Run run, long credentialRevision, Runnable write) {
        transactions.executeWithoutResult(_ -> {
            if (current(run.tenantId(), run.credentialId(), credentialRevision) && directory.unchanged(run))
                write.run();
        });
    }

    private boolean current(TenantId tenant, CredentialId credential, long credentialRevision) {
        return credentials.lock(tenant, credential)
                .filter(row -> row.usable() && row.revision() == credentialRevision).isPresent();
    }
}
