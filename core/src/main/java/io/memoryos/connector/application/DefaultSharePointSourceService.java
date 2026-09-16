package io.memoryos.connector.application;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointConnectionService;
import io.memoryos.connector.SharePointSourceService;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.connector.SourceSelectionProcessor.Work;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSharePointCredentialRepository;
import io.memoryos.connector.persistence.JdbcSharePointSelectionRepository;
import io.memoryos.connector.persistence.JdbcSharePointSourceRepository;
import io.memoryos.connector.persistence.JdbcSharePointSourceRepository.ResolvedRoot;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceGroupRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.connector.persistence.JdbcSourceSyncRepository;
import io.memoryos.iam.group.Authority;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.group.GroupId;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating and reconfiguring a SharePoint Source. Everything an administrator submits is checked locally,
 * accepted as an operation, and only applied once the worker has resolved every address with Microsoft.
 */
@Service
public class DefaultSharePointSourceService implements SharePointSourceService {
    private final SourceAccessPolicy sourceAccess;
    private final IamAuthorization authorization;
    private final SharePointConnectionService connections;
    private final JdbcSharePointSourceRepository sharePoint;
    private final JdbcSharePointSelectionRepository selections;
    private final JdbcSharePointCredentialRepository credentials;
    private final JdbcSourceGroupRepository sourceGroups;
    private final JdbcSourceRepository sources;
    private final JdbcSourceSyncRepository sync;
    private final JdbcIndexAttemptRepository indexing;
    private final JdbcSourceDocumentRepository documents;
    private final SharePointSelectionPolicy policy;
    private final TransactionTemplate transactions;

    public DefaultSharePointSourceService(SourceAccessPolicy sourceAccess, IamAuthorization authorization,
            SharePointConnectionService connections,
            JdbcSharePointSourceRepository sharePoint, JdbcSharePointSelectionRepository selections,
            JdbcSharePointCredentialRepository credentials, JdbcSourceGroupRepository sourceGroups,
            JdbcSourceRepository sources, JdbcSourceSyncRepository sync, JdbcIndexAttemptRepository indexing,
            JdbcSourceDocumentRepository documents, SharePointSelectionPolicy policy,
            PlatformTransactionManager transactionManager) {
        this.sourceAccess = sourceAccess;
        this.authorization = authorization;
        this.connections = connections;
        this.sharePoint = sharePoint;
        this.selections = selections;
        this.credentials = credentials;
        this.sourceGroups = sourceGroups;
        this.sources = sources;
        this.sync = sync;
        this.indexing = indexing;
        this.documents = documents;
        this.policy = policy;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public SelectionReceipt create(ActorId actor, UUID requestId, String name, CredentialId credentialId,
            Scope scope, SourceAccess access, List<GroupId> groupIds) {
        String sourceName = requireName(name);
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(requestId, "requestId");
        policy.requireScope(scope);
        policy.requireSize(sourceName, scope);
        var tenant = sourceAccess.creation(actor, access, groupIds).authority().tenantId();
        return Objects.requireNonNull(transactions.execute(_ -> {
            var creation = sourceAccess.lockCreation(actor, access, groupIds);
            if (!tenant.equals(creation.authority().tenantId())) throw SourceException.notFound();
            var groups = creation.groupIds().stream()
                    .sorted(java.util.Comparator.comparing(group -> group.value().toString())).toList();
            var credential = credentials.lock(tenant, credentialId).orElseThrow(SourceException::notFound);
            if (!credential.usable()) throw SourceException.conflict("SharePoint credential is unavailable");
            String hash = hash("CREATE", sourceName, credentialId.value().toString(), scope,
                    groups.stream().map(group -> group.value().toString()).toList());
            var existing = selections.receipt(tenant, actor, requestId, hash);
            if (existing.isPresent()) return existing.get();
            return selections.submit(tenant, actor, requestId, hash, new SourceId(UUID.randomUUID()), credentialId,
                    credential.revision(), 0, scope, sourceName, access, groups, policy.value());
        }));
    }

    @Override
    public SelectionReceipt replaceScope(ActorId actor, UUID requestId, SourceId source, long expectedScopeRevision,
            long expectedCredentialRevision, Scope scope) {
        Objects.requireNonNull(requestId, "requestId");
        policy.requireScope(scope);
        policy.requireSize("scope", scope);
        var tenant = management(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.lockManage(actor, source);
            sharePoint.lock(tenant, source);
            var configuration = sharePoint.configuration(tenant, source);
            if (configuration.scopeRevision() != expectedScopeRevision) throw staleScope();
            var credentialId = sharePoint.credentialId(tenant, source);
            var credential = credentials.lock(tenant, credentialId).orElseThrow(SourceException::notFound);
            if (!credential.usable()) throw SourceException.conflict("SharePoint credential is unavailable");
            if (credential.revision() != expectedCredentialRevision) {
                throw SourceException.conflict("SharePoint credential revision is stale");
            }
            String hash = hash("SCOPE", source.value().toString(), credentialId.value().toString(), scope,
                    List.of(String.valueOf(expectedScopeRevision)));
            var existing = selections.receipt(tenant, actor, requestId, hash);
            if (existing.isPresent()) return existing.get();
            return selections.submit(tenant, actor, requestId, hash, source, credentialId, credential.revision(),
                    expectedScopeRevision, scope, null, null, List.of(), policy.value());
        }));
    }

    @Override
    public Configuration configuration(ActorId actor, SourceId source) {
        var tenant = readable(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.read(actor, source);
            sharePoint.lock(tenant, source);
            return configuration(tenant, source);
        }));
    }

    private Configuration configuration(TenantId tenant, SourceId source) {
        var state = connections.state(tenant, source);
        return sharePoint.configuration(tenant, source).view(source, state.credentialId(), state.name(),
                state.status(), state.credentialRevision(), sharePoint.exclusions(tenant, source, "SITE"),
                sharePoint.exclusions(tenant, source, "PATH"), selections.pending(tenant, source));
    }

    @Override
    public RootPage roots(ActorId actor, SourceId source, @Nullable String cursor, int size) {
        var tenant = readable(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.read(actor, source);
            return sharePoint.roots(tenant, source, cursor, size);
        }));
    }

    @Override
    public SelectionPolicy selectionPolicy(ActorId actor) {
        authorization.require(actor, IamCapability.SOURCES_MANAGE, true);
        return policy.value();
    }

    @Override
    public int selectionRequestByteLimit() {
        return policy.value().maxRequestBytes();
    }

    @Override
    public SelectionReceipt selectionRequest(ActorId actor, UUID requestId) {
        var tenant = authorization.require(actor, IamCapability.SOURCES_MANAGE, true).tenantId();
        return Objects.requireNonNull(transactions.execute(_ ->
                selections.receipt(tenant, actor, requestId, null).orElseThrow(SourceException::notFound)));
    }

    @Override
    public Configuration updateSchedule(ActorId actor, SourceId source, long expectedScheduleRevision,
            int syncIntervalMinutes, int pruneIntervalHours) {
        var tenant = management(actor, source);
        if (syncIntervalMinutes < 1 || pruneIntervalHours < 0 || pruneIntervalHours > 8760) {
            throw SourceException.invalid("Choose a synchronization interval of at least one minute and a prune"
                    + " interval of at most one year.", "SharePoint schedule outside deployment bounds");
        }
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.lockManage(actor, source);
            sharePoint.lock(tenant, source);
            sharePoint.updateSchedule(tenant, source, expectedScheduleRevision, syncIntervalMinutes, pruneIntervalHours);
            return configuration(tenant, source);
        }));
    }

    @Override
    public Configuration setPaused(ActorId actor, SourceId source, long expectedScheduleRevision, boolean paused) {
        var tenant = management(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.lockManage(actor, source);
            sharePoint.lock(tenant, source);
            sharePoint.setPaused(tenant, source, expectedScheduleRevision, paused);
            if (paused) sync.cancel(tenant, source);
            return configuration(tenant, source);
        }));
    }

    @Override
    public SourceOperationView synchronize(ActorId actor, SourceId source) {
        var tenant = management(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.lockManage(actor, source);
            sharePoint.lock(tenant, source);
            var state = connections.state(tenant, source);
            sharePoint.requestSynchronization(tenant, source);
            return sync.enqueue(tenant, source, state.credentialRevision(), SourceRunTrigger.MANUAL, actor);
        }));
    }

    /** The accepted request must still describe the current Source, or the worker stops instead of applying it. */
    void requireIntent(Work work, JdbcSharePointSelectionRepository.Intent intent) {
        if (!selections.current(work)) throw SourceException.conflict("SharePoint selection lost its claim");
        if (intent.credentialId() == null) throw SourceException.conflict("SharePoint selection lost its credential");
        var credential = credentials.lock(work.tenantId(), new CredentialId(intent.credentialId()))
                .orElseThrow(SourceException::notFound);
        if (!credential.usable() || credential.revision() != intent.credentialRevision()) {
            throw SourceException.conflict("SharePoint credential changed while verifying the scope");
        }
        if (intent.name() == null) {
            sharePoint.lock(work.tenantId(), work.sourceId());
            if (sharePoint.configuration(work.tenantId(), work.sourceId()).scopeRevision() != intent.scopeRevision()) {
                throw staleScope();
            }
        }
    }

    /** Applies a verified scope: a new Source starts synchronizing, an existing one re-reads what it holds. */
    void activate(Work work, JdbcSharePointSelectionRepository.Intent intent, Scope scope, List<ResolvedRoot> roots,
            @Nullable String tenantHost) {
        requireIntent(work, intent);
        var tenant = work.tenantId();
        var source = work.sourceId();
        if (intent.name() != null) {
            var access = Objects.requireNonNull(intent.access());
            var creation = sourceAccess.creation(intent.actorId(), access, intent.groupIds());
            sharePoint.create(tenant, source, intent.actorId(),
                    creation.authority().authority() == Authority.GLOBAL ? null : intent.actorId(), intent.name(),
                    new CredentialId(Objects.requireNonNull(intent.credentialId())), access, scope, roots, tenantHost);
            sourceGroups.replace(tenant, source, creation.groupIds());
            sync.enqueue(tenant, source, intent.credentialRevision(), SourceRunTrigger.INITIAL, intent.actorId());
        } else {
            sync.cancel(tenant, source);
            sharePoint.replaceScope(tenant, source, intent.scopeRevision(), scope, roots, tenantHost);
            indexing.cancelForSource(tenant, source);
            documents.invalidateSource(tenant, source);
            sources.recomputeStatus(tenant, source, false);
        }
        selections.finish(work, "SUCCEEDED", null);
    }

    private TenantId management(ActorId actor, SourceId source) {
        return sourceAccess.manage(actor, source).tenantId();
    }

    private TenantId readable(ActorId actor, SourceId source) {
        return sourceAccess.read(actor, source).tenantId();
    }

    private static SourceException staleScope() {
        return SourceException.conflict("SharePoint scope revision is stale");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 120) {
            throw SourceException.invalid("Source name must contain 1 to 120 characters.", "invalid source name");
        }
        return name.strip();
    }

    /** Identifies a request so a retry of the same content recovers its receipt instead of starting again. */
    private static String hash(String operation, String first, String second, Scope scope, List<String> extra) {
        var builder = new StringBuilder(operation).append(' ').append(first).append(' ').append(second)
                .append(' ').append(scope.scopeMode()).append(' ').append(scope.includeDocuments())
                .append(' ').append(scope.includePages()).append(' ').append(scope.syncIntervalMinutes())
                .append(' ').append(scope.pruneIntervalHours());
        for (String url : scope.siteUrls()) builder.append(' ').append(url);
        builder.append('');
        for (String pattern : scope.excludedSites()) builder.append(' ').append(pattern);
        builder.append('');
        for (String pattern : scope.excludedPaths()) builder.append(' ').append(pattern);
        for (String value : extra) builder.append('').append(value);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(builder.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required to identify a selection request", exception);
        }
    }

    SourceOperationView operation(TenantId tenant, SourceOperationId operation) {
        return selections.find(tenant, operation).orElseThrow(SourceException::notFound);
    }
}
