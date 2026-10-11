package io.memoryos.connector.lark;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkException;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.LarkSourceService;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.connector.SourceSelectionProcessor.Work;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.lark.persistence.JdbcLarkCredentialRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSelectionRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSourceRepository;
import io.memoryos.connector.source.SourceAccessPolicy;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.source.persistence.JdbcSourceGroupRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.connector.sync.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.iam.Authority;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.Sha256;
import io.memoryos.shared.TenantId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating and reconfiguring a Lark Suite Source. Everything an administrator submits is checked locally, accepted
 * as an operation, and only applied once the worker has read every root with the credential's app.
 */
@Service
public class DefaultLarkSourceService implements LarkSourceService {
    /** The most Wiki spaces offered to choose from. */
    private static final int MAX_SPACE_OPTIONS = 500;

    private final SourceAccessPolicy sourceAccess;
    private final IamAuthorization authorization;
    private final LarkConnectionService connections;
    private final JdbcLarkSourceRepository lark;
    private final JdbcLarkSelectionRepository selections;
    private final JdbcLarkCredentialRepository credentials;
    private final JdbcSourceGroupRepository sourceGroups;
    private final JdbcSourceRepository sources;
    private final JdbcSourceSyncRepository sync;
    private final JdbcIndexAttemptRepository indexing;
    private final JdbcSourceDocumentRepository documents;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;

    public DefaultLarkSourceService(SourceAccessPolicy sourceAccess, IamAuthorization authorization,
            LarkConnectionService connections, JdbcLarkSourceRepository lark,
            JdbcLarkSelectionRepository selections, JdbcLarkCredentialRepository credentials,
            JdbcSourceGroupRepository sourceGroups, JdbcSourceRepository sources, JdbcSourceSyncRepository sync,
            JdbcIndexAttemptRepository indexing, JdbcSourceDocumentRepository documents,
            PlatformTransactionManager transactionManager, AuditTrail audit) {
        this.sourceAccess = sourceAccess;
        this.authorization = authorization;
        this.connections = connections;
        this.lark = lark;
        this.selections = selections;
        this.credentials = credentials;
        this.sourceGroups = sourceGroups;
        this.sources = sources;
        this.sync = sync;
        this.indexing = indexing;
        this.documents = documents;
        this.transactions = new TransactionTemplate(transactionManager);
        this.audit = audit;
    }

    @Override
    public SelectionReceipt create(ActorId actor, UUID requestId, String name, CredentialId credentialId,
            Scope scope, SourceAccess access, List<GroupId> groupIds) {
        String sourceName = requireName(name);
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(requestId, "requestId");
        requireSchedule(scope.syncIntervalMinutes());
        var tenant = sourceAccess.creation(actor, SourceType.LARK, access, groupIds).authority().tenantId();
        return Objects.requireNonNull(transactions.execute(_ -> {
            var creation = sourceAccess.lockCreation(actor, SourceType.LARK, access, groupIds);
            if (!tenant.equals(creation.authority().tenantId())) throw SourceException.notFound();
            var groups = creation.groupIds().stream()
                    .sorted(Comparator.comparing(group -> group.value().toString())).toList();
            var credential = credentials.lock(tenant, credentialId).orElseThrow(SourceException::notFound);
            requireCredentialOwner(creation.authority(), actor, credential.ownerActorId());
            if (!credential.usable()) throw SourceException.conflict("Lark credential is unavailable");
            var roots = roots(scope, credential.region());
            String hash = hash("CREATE", sourceName, credentialId.value().toString(), roots,
                    scope.syncIntervalMinutes(), groups.stream().map(group -> group.value().toString()).toList());
            var existing = selections.receipt(tenant, actor, requestId, hash);
            if (existing.isPresent()) return existing.get();
            var source = new SourceId(UUID.randomUUID());
            var submitted = selections.submit(tenant, actor, requestId, hash, source, credentialId,
                    credential.revision(), 0, roots, scope.syncIntervalMinutes(), sourceName, access, groups,
                    MAX_ROOTS);
            record(tenant, actor, AuditAction.SOURCE_CREATE, source, sourceName, event -> event
                    .detail("provider", SourceType.LARK.name()).detail("access", access.name())
                    .detail("groups", groups.size()));
            return submitted;
        }));
    }

    @Override
    public SelectionReceipt replaceScope(ActorId actor, UUID requestId, SourceId source, long expectedScopeRevision,
            long expectedCredentialRevision, Scope scope) {
        Objects.requireNonNull(requestId, "requestId");
        requireSchedule(scope.syncIntervalMinutes());
        var tenant = management(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.lockManage(actor, source);
            lark.lock(tenant, source);
            if (lark.configuration(tenant, source).scopeRevision() != expectedScopeRevision) throw staleScope();
            var credentialId = lark.credentialId(tenant, source);
            var credential = credentials.lock(tenant, credentialId).orElseThrow(SourceException::notFound);
            if (!credential.usable()) throw SourceException.conflict("Lark credential is unavailable");
            if (credential.revision() != expectedCredentialRevision)
                throw SourceException.conflict("Lark credential revision is stale");
            var roots = roots(scope, credential.region());
            String hash = hash("SCOPE", source.value().toString(), credentialId.value().toString(), roots,
                    scope.syncIntervalMinutes(), List.of(String.valueOf(expectedScopeRevision)));
            var existing = selections.receipt(tenant, actor, requestId, hash);
            if (existing.isPresent()) return existing.get();
            var submitted = selections.submit(tenant, actor, requestId, hash, source, credentialId,
                    credential.revision(), expectedScopeRevision, roots, scope.syncIntervalMinutes(), null, null,
                    List.of(), MAX_ROOTS);
            record(tenant, actor, AuditAction.SOURCE_UPDATE, source, null, event -> event.detail("change", "SCOPE"));
            return submitted;
        }));
    }

    @Override
    public Configuration configuration(ActorId actor, SourceId source) {
        var tenant = readable(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.read(actor, source);
            lark.lock(tenant, source);
            return configuration(tenant, source);
        }));
    }

    private Configuration configuration(TenantId tenant, SourceId source) {
        var state = connections.state(tenant, source);
        return lark.configuration(tenant, source).view(source, state.credentialId(), state.name(), state.status(),
                state.credentialRevision(), selections.pending(tenant, source));
    }

    @Override
    public List<RootView> roots(ActorId actor, SourceId source) {
        var tenant = readable(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.read(actor, source);
            return lark.roots(tenant, source);
        }));
    }

    @Override
    public List<SpaceOption> spaces(ActorId actor, CredentialId credentialId) {
        var tenant = Objects.requireNonNull(transactions.execute(_ -> {
            IamAccess access = authorization.require(actor, IamCapability.SOURCES_MANAGE, true);
            var stored = credentials.lock(access.tenantId(), credentialId).orElseThrow(SourceException::notFound);
            requireCredentialOwner(access, actor, stored.ownerActorId());
            return access.tenantId();
        }));
        // Lark is read with no database transaction open.
        try (var connection = connections.openCredential(tenant, credentialId)) {
            var spaces = new ArrayList<SpaceOption>();
            String page = null;
            do {
                var listed = connection.session().spaces(page);
                for (var space : listed.spaces()) {
                    if (spaces.size() >= MAX_SPACE_OPTIONS) return List.copyOf(spaces);
                    spaces.add(new SpaceOption(space.spaceId(), space.name()));
                }
                page = listed.pageToken();
            } while (page != null);
            return List.copyOf(spaces);
        } catch (LarkProviderException exception) {
            throw switch (exception.failure()) {
                case AUTHENTICATION -> LarkException.needsUpdate();
                case AUTHORIZATION -> LarkException.scopeMissing();
                case QUOTA -> LarkException.throttled();
                default -> LarkException.unavailable();
            };
        }
    }

    @Override
    public SelectionReceipt selectionRequest(ActorId actor, UUID requestId) {
        var tenant = authorization.require(actor, IamCapability.SOURCES_MANAGE, true).tenantId();
        return Objects.requireNonNull(transactions.execute(_ ->
                selections.receipt(tenant, actor, requestId, null).orElseThrow(SourceException::notFound)));
    }

    @Override
    public Configuration updateSchedule(ActorId actor, SourceId source, long expectedScheduleRevision,
            int syncIntervalMinutes) {
        var tenant = management(actor, source);
        requireSchedule(syncIntervalMinutes);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.lockManage(actor, source);
            lark.lock(tenant, source);
            int before = lark.configuration(tenant, source).syncIntervalMinutes();
            lark.updateSchedule(tenant, source, expectedScheduleRevision, syncIntervalMinutes);
            // A request that leaves the schedule as it was is not a change, as on Google Drive.
            if (before != syncIntervalMinutes)
                record(tenant, actor, AuditAction.SOURCE_UPDATE, source, null, event -> event
                        .detail("change", "SCHEDULE").detail("before", Map.of("syncMinutes", before))
                        .detail("after", Map.of("syncMinutes", syncIntervalMinutes)));
            return configuration(tenant, source);
        }));
    }

    @Override
    public Configuration setPaused(ActorId actor, SourceId source, long expectedScheduleRevision, boolean paused) {
        var tenant = management(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.lockManage(actor, source);
            lark.lock(tenant, source);
            boolean before = lark.configuration(tenant, source).syncPaused();
            lark.setPaused(tenant, source, expectedScheduleRevision, paused);
            if (paused) sync.cancel(tenant, source, "SOURCE_PAUSED");
            if (before != paused)
                record(tenant, actor, paused ? AuditAction.SOURCE_PAUSE : AuditAction.SOURCE_RESUME,
                        source, null, event -> event);
            return configuration(tenant, source);
        }));
    }

    @Override
    public SourceOperationView synchronize(ActorId actor, SourceId source) {
        var tenant = management(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sourceAccess.lockManage(actor, source);
            lark.lock(tenant, source);
            var state = connections.state(tenant, source);
            lark.requestSynchronization(tenant, source);
            return sync.enqueue(tenant, source, state.credentialRevision(), SourceRunTrigger.MANUAL, actor);
        }));
    }

    /** The accepted request must still describe the current Source, or the worker stops instead of applying it. */
    void requireIntent(Work work, JdbcLarkSelectionRepository.Intent intent) {
        if (!selections.current(work)) throw SourceException.conflict("Lark selection lost its claim");
        if (intent.credentialId() == null) throw SourceException.conflict("Lark selection lost its credential");
        var credential = credentials.lock(work.tenantId(), new CredentialId(intent.credentialId()))
                .orElseThrow(SourceException::notFound);
        if (!credential.usable() || credential.revision() != intent.credentialRevision())
            throw SourceException.conflict("Lark credential changed while verifying the scope");
        if (intent.name() == null) {
            lark.lock(work.tenantId(), work.sourceId());
            if (lark.configuration(work.tenantId(), work.sourceId()).scopeRevision() != intent.scopeRevision())
                throw staleScope();
        }
    }

    /** Applies a verified scope: a new Source starts synchronizing, an existing one re-reads what it holds. */
    void activate(Work work, JdbcLarkSelectionRepository.Intent intent, List<RootView> roots) {
        requireIntent(work, intent);
        var tenant = work.tenantId();
        var source = work.sourceId();
        if (intent.name() != null) {
            var access = Objects.requireNonNull(intent.access());
            var creation = sourceAccess.creation(intent.actorId(), SourceType.LARK, access, intent.groupIds());
            lark.create(tenant, source, intent.actorId(),
                    creation.authority().authority() == Authority.GLOBAL ? null : intent.actorId(), intent.name(),
                    new CredentialId(Objects.requireNonNull(intent.credentialId())), access,
                    intent.syncIntervalMinutes(), roots);
            sourceGroups.replace(tenant, source, creation.groupIds());
            sync.enqueue(tenant, source, intent.credentialRevision(), SourceRunTrigger.INITIAL, intent.actorId());
        } else {
            sync.supersede(tenant, source);
            lark.replaceScope(tenant, source, intent.scopeRevision(), intent.syncIntervalMinutes(), roots);
            indexing.cancelForSource(tenant, source);
            documents.invalidateSource(tenant, source);
            sources.recomputeStatus(tenant, source, false);
        }
        selections.finish(work, "SUCCEEDED", null);
    }

    /** The roots of a request as tokens, checked without a call to Lark: at least one, none twice. */
    private static List<RootView> roots(Scope scope, LarkGateway.Region region) {
        if (scope.roots().isEmpty())
            throw SourceException.invalid("Choose at least one Wiki space or Drive folder.", "Lark scope names no root");
        if (scope.roots().size() > MAX_ROOTS)
            throw SourceException.invalid("That is more spaces and folders than one Source may name.",
                    "Lark roots exceed the maximum");
        var seen = new LinkedHashSet<String>();
        var roots = new ArrayList<RootView>(scope.roots().size());
        for (Root root : scope.roots()) {
            Objects.requireNonNull(root.kind(), "kind");
            String token = root.kind() == RootKind.WIKI_SPACE
                    ? LarkLinks.spaceId(root.value()) : LarkLinks.folderToken(root.value(), region);
            if (!seen.add(root.kind() + ":" + token)) throw SourceException.overlappingRoots();
            roots.add(new RootView(root.kind(), token, null));
        }
        return List.copyOf(roots);
    }

    private TenantId management(ActorId actor, SourceId source) {
        return sourceAccess.manage(actor, source).tenantId();
    }

    private TenantId readable(ActorId actor, SourceId source) {
        return sourceAccess.read(actor, source).tenantId();
    }

    private static void requireCredentialOwner(IamAccess access, ActorId actor, @Nullable UUID ownerActorId) {
        if (access.authority() != Authority.GLOBAL && !actor.value().equals(ownerActorId)) throw SourceException.notFound();
    }

    private static void requireSchedule(int syncIntervalMinutes) {
        if (syncIntervalMinutes < 1 || syncIntervalMinutes > 525_600)
            throw SourceException.invalid("Choose a synchronization interval between one minute and one year.",
                    "Lark schedule outside deployment bounds");
    }

    private static SourceException staleScope() {
        return SourceException.conflict("Lark scope revision is stale");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 120)
            throw SourceException.invalid("Source name must contain 1 to 120 characters.", "invalid source name");
        return name.strip();
    }

    /** Identifies a request so a retry of the same content recovers its receipt instead of starting again. */
    private static String hash(String operation, String first, String second, List<RootView> roots,
            int syncIntervalMinutes, List<String> extra) {
        var builder = new StringBuilder(operation).append('\u0000').append(first).append('\u0000').append(second)
                .append('\u0000').append(syncIntervalMinutes);
        for (RootView root : roots) builder.append('\u0000').append(root.kind()).append(':').append(root.token());
        for (String value : extra) builder.append('\u0003').append(value);
        return Sha256.hex(builder.toString());
    }

    /** Records a Source change; a Lark Source is created and re-scoped by a request the Worker settles. */
    private void record(TenantId tenant, ActorId actor, AuditAction action, SourceId source, @Nullable String name,
                        UnaryOperator<AuditRecord.Builder> details) {
        String label = name != null ? name
                : sources.auditView(tenant, source).map(JdbcSourceRepository.AuditView::name).orElse(null);
        audit.record(details.apply(AuditRecord.of(action, tenant).actor(actor)
                .resource("SOURCE", source.value(), label)).build());
    }
}
