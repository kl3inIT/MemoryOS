package io.memoryos.connector.sharepoint;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointException;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SharePointSelectionProcessor;
import io.memoryos.connector.SharePointSourceService.RootKind;
import io.memoryos.connector.SharePointSourceService.Scope;
import io.memoryos.connector.SharePointSourceService.ScopeMode;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSelectionRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSelectionRepository.Entry;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSelectionRepository.Intent;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSourceRepository.ResolvedRoot;
import io.memoryos.shared.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves every address of an accepted scope request against Microsoft. A library is matched by the path
 * of its own URL rather than its display name, so a site in any language resolves.
 */
@Service
public class DefaultSharePointSelectionProcessor implements SharePointSelectionProcessor {
    /** A batch hands the work back when it has run this long, so one claim never holds a worker. */
    private static final long BATCH_MILLIS = 30_000;

    private final JdbcSharePointSelectionRepository selections;
    private final DefaultSharePointSourceService sources;
    private final SharePointConnectionService connections;
    private final TransactionTemplate transactions;

    public DefaultSharePointSelectionProcessor(JdbcSharePointSelectionRepository selections,
            DefaultSharePointSourceService sources, SharePointConnectionService connections,
            PlatformTransactionManager transactionManager) {
        this.selections = selections;
        this.sources = sources;
        this.connections = connections;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<Work> claim(TenantId tenant, SourceOperationId operation, UUID delivery) {
        return Objects.requireNonNull(transactions.execute(_ -> selections.claim(tenant, operation, delivery)));
    }

    @Override
    public boolean renew(Work work) {
        return Boolean.TRUE.equals(transactions.execute(_ -> selections.renew(work)));
    }

    @Override
    public Result execute(Work work) {
        long started = System.nanoTime();
        var intent = selections.intent(work);
        try {
            transactions.executeWithoutResult(_ -> sources.requireIntent(work, intent));
            String tenantHost = null;
            try (var connection = connections.openCredential(work.tenantId(),
                    new CredentialId(Objects.requireNonNull(intent.credentialId())))) {
                if (connection.credentialRevision() != intent.credentialRevision()) throw staleCredential();
                transactions.executeWithoutResult(_ -> sources.requireIntent(work, intent));
                tenantHost = connection.tenantHost() == null
                        ? connection.session().root().hostname() : connection.tenantHost();
                verify(work, intent, connection.session(), started);
            }
            var entries = selections.entries(work);
            var roots = new ArrayList<ResolvedRoot>();
            for (Entry entry : entries) {
                if (!entry.root()) continue;
                if (!entry.verified()) throw new ContinueBatch();
                roots.add(new ResolvedRoot(RootKind.valueOf(Objects.requireNonNull(entry.rootKind())), entry.value(),
                        entry.siteId(), entry.driveId(), entry.itemId(), entry.displayName()));
            }
            var scope = scope(intent, entries);
            String host = tenantHost;
            transactions.executeWithoutResult(_ -> sources.activate(work, intent, scope, roots, host));
            return Result.COMPLETED;
        } catch (ContinueBatch exception) {
            transactions.executeWithoutResult(_ -> selections.continueLater(work, elapsed(started), null));
            return Result.CONTINUED;
        } catch (SharePointProviderException exception) {
            return providerFailure(work, intent, exception, started);
        } catch (BusinessException exception) {
            boolean stale = exception.category() != FailureCategory.VALIDATION;
            transactions.executeWithoutResult(_ ->
                    selections.finish(work, stale ? "SUPERSEDED" : "FAILED", exception.code()));
            return stale ? Result.SUPERSEDED : Result.FAILED;
        }
    }

    private Result providerFailure(Work work, Intent intent, SharePointProviderException exception, long started) {
        String code = "SOURCE_SHAREPOINT_" + exception.failure().name();
        if (exception.failure() == SharePointProviderException.Failure.AUTHENTICATION && intent.credentialId() != null) {
            connections.authenticationFailed(work.tenantId(), new CredentialId(intent.credentialId()),
                    intent.credentialRevision());
        }
        boolean retryable = switch (exception.failure()) {
            case QUOTA, UNAVAILABLE, RESYNC_REQUIRED -> true;
            default -> false;
        };
        if (retryable) {
            transactions.executeWithoutResult(_ -> selections.continueLater(work, elapsed(started), code));
            return Result.CONTINUED;
        }
        transactions.executeWithoutResult(_ -> selections.finish(work, "FAILED", code));
        return Result.FAILED;
    }

    /** Resolves the roots that are still unverified, checkpointing each one as it succeeds. */
    private void verify(Work work, Intent intent, SharePointProvider.Session session, long started) {
        for (Entry entry : selections.entries(work)) {
            if (!entry.root() || entry.verified()) continue;
            if (elapsed(started) >= BATCH_MILLIS) throw new ContinueBatch();
            transactions.executeWithoutResult(_ -> sources.requireIntent(work, intent));
            var url = SharePointUrl.parse(entry.value());
            selections.reserveRequest(work);
            var site = session.site(url.host(), url.sitePath());
            String driveId = null;
            String itemId = null;
            String displayName = site.displayName();
            if (url.kind() != SharePointUrl.Kind.SITE) {
                selections.reserveRequest(work);
                var library = library(session, site.siteId(), url);
                driveId = library.driveId();
                displayName = library.name();
                if (url.kind() == SharePointUrl.Kind.FOLDER) {
                    selections.reserveRequest(work);
                    var folder = session.folder(driveId, url.folderSegments());
                    itemId = folder.itemId();
                    displayName = folder.name();
                }
            }
            String resolvedDrive = driveId;
            String resolvedItem = itemId;
            String resolvedName = displayName;
            transactions.executeWithoutResult(_ -> {
                sources.requireIntent(work, intent);
                selections.verified(work, entry, url.kind().name(), site.siteId(), resolvedDrive, resolvedItem,
                        resolvedName);
            });
        }
    }

    /** Matches the library by the path of its URL, which stays the same whatever the site language is. */
    private static SharePointProvider.Library library(SharePointProvider.Session session, String siteId,
            SharePointUrl url) {
        String wanted = url.sitePath() + "/" + Objects.requireNonNull(url.librarySegment());
        for (var library : session.libraries(siteId)) {
            if (library.path().equalsIgnoreCase(wanted)) return library;
        }
        throw SharePointException.invalidRootUrl("That library is not on this site, or the application cannot read it.");
    }

    private static Scope scope(Intent intent, List<Entry> entries) {
        var roots = new ArrayList<String>();
        var excludedSites = new ArrayList<String>();
        var excludedPaths = new ArrayList<String>();
        for (Entry entry : entries) {
            switch (entry.kind().toUpperCase(Locale.ROOT)) {
                case JdbcSharePointSelectionRepository.ROOT -> roots.add(entry.value());
                case JdbcSharePointSelectionRepository.EXCLUDED_SITE -> excludedSites.add(entry.value());
                case JdbcSharePointSelectionRepository.EXCLUDED_PATH -> excludedPaths.add(entry.value());
                default -> throw new IllegalStateException("unknown SharePoint selection entry " + entry.kind());
            }
        }
        return new Scope(intent.scopeMode() == ScopeMode.ALL_SITES ? ScopeMode.ALL_SITES : ScopeMode.SPECIFIC,
                roots, excludedSites, excludedPaths, intent.includeDocuments(), intent.includePages(),
                intent.syncIntervalMinutes(), intent.pruneIntervalHours());
    }

    private static SourceException staleCredential() {
        return SourceException.conflict("SharePoint credential changed while verifying the scope");
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    /** Signals that the batch ran long enough and the rest continues under a fresh claim. */
    private static final class ContinueBatch extends RuntimeException {
        private ContinueBatch() { super(null, null, false, false); }
    }
}
