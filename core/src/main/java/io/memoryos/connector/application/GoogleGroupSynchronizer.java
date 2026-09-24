package io.memoryos.connector.application;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveConnectionService;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.persistence.JdbcGoogleGroupRepository;
import io.memoryos.connector.persistence.JdbcGoogleGroupRepository.Cursor;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads the Google Groups of a service account's Workspace one Directory page at a time, as its primary admin, so
 * a Drive sync step never exceeds its budget. Pages are read outside any transaction and applied under the
 * credential row lock only while the run still stands where the page was read, so Sources sharing the credential
 * never apply a page twice.
 */
@Service
public class GoogleGroupSynchronizer {
    private final JdbcGoogleGroupRepository groups;
    private final GoogleDriveConnectionService connections;
    private final TransactionTemplate transactions;
    private final Duration interval;

    public GoogleGroupSynchronizer(JdbcGoogleGroupRepository groups, GoogleDriveConnectionService connections,
            PlatformTransactionManager transactionManager,
            @Value("${memoryos.google-drive.group-sync-interval:PT1H}") Duration interval) {
        if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("group sync interval must be positive");
        this.groups = groups;
        this.connections = connections;
        this.transactions = new TransactionTemplate(transactionManager);
        this.interval = interval;
    }

    /**
     * Advances the credential's group sync by at most one Directory page. Returns {@code false} when nothing is
     * due, so the caller can stop asking during this execution.
     */
    public boolean advance(TenantId tenant, CredentialId credential, long credentialRevision, String adminEmail,
            GoogleDriveProvider.Session session) {
        var cursor = transactions.execute(_ -> {
            if (!connections.currentCredential(tenant, credential, credentialRevision)) return null;
            return groups.running(tenant, credential)
                    .or(() -> groups.due(tenant, credential, interval)
                            ? Optional.of(groups.start(tenant, credential)) : Optional.empty())
                    .orElse(null);
        });
        if (cursor == null) return false;
        try {
            if (!cursor.groupsListed()) {
                var page = session.groups(domain(adminEmail), cursor.groupsPageToken());
                apply(cursor, credentialRevision, () -> groups.recordGroups(cursor, page.emails(), page.nextPageToken()));
            } else if (cursor.groupEmail() != null) {
                readMembers(cursor, credentialRevision, session);
            } else {
                apply(cursor, credentialRevision, () -> groups.complete(cursor));
            }
        } catch (GoogleDriveProviderException exception) {
            apply(cursor, credentialRevision, () -> groups.fail(cursor, "SOURCE_GOOGLE_" + exception.failure().name(),
                    exception.getMessage()));
            return false;
        }
        return true;
    }

    private void readMembers(Cursor cursor, long credentialRevision, GoogleDriveProvider.Session session) {
        GoogleDriveProvider.MemberPage page;
        try {
            page = session.groupMembers(Objects.requireNonNull(cursor.groupEmail()), cursor.membersPageToken());
        } catch (GoogleDriveProviderException exception) {
            // A group deleted after it was listed simply has no members in this generation.
            if (exception.failure() != GoogleDriveProviderException.Failure.NOT_FOUND) throw exception;
            page = new GoogleDriveProvider.MemberPage(List.of(), false, null);
        }
        var members = page;
        apply(cursor, credentialRevision, () -> groups.recordMembers(cursor, members.emails(), members.wholeDomain(),
                members.nextPageToken()));
    }

    private void apply(Cursor cursor, long credentialRevision, Runnable write) {
        transactions.executeWithoutResult(_ -> {
            if (connections.currentCredential(cursor.tenantId(), cursor.credentialId(), credentialRevision)
                    && groups.unchanged(cursor)) {
                write.run();
            }
        });
    }

    private static String domain(String adminEmail) {
        return adminEmail.substring(adminEmail.indexOf('@') + 1).toLowerCase(Locale.ROOT);
    }
}
