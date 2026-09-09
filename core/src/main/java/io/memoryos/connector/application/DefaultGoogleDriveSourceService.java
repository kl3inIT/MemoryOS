package io.memoryos.connector.application;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveConnectionService;
import io.memoryos.connector.GoogleDriveLinkReader;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.GoogleDriveSourceService;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.persistence.JdbcGoogleDriveSourceRepository;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.connector.persistence.JdbcSourceSyncRepository;
import io.memoryos.connector.persistence.JdbcGoogleDriveSelectionRepository;
import io.memoryos.connector.persistence.JdbcGoogleDriveCredentialRepository;
import io.memoryos.connector.GoogleDriveSelectionProcessor.Work;
import io.memoryos.connector.SourceRunTrigger;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import io.memoryos.identity.ActorId;
import io.memoryos.tenant.TenantAccessResolver;
import io.memoryos.tenant.TenantId;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DefaultGoogleDriveSourceService implements GoogleDriveSourceService {
    private static final Pattern DRIVE_PATH = Pattern.compile(
            "/(?:drive/(?:u/[0-9]+/)?folders|file/(?:u/[0-9]+/)?d)/([A-Za-z0-9_-]{1,256})(?:/(?:view|edit|preview))?/?");
    private static final Pattern DOCUMENT_PATH = Pattern.compile(
            "/(?:document|spreadsheets|presentation)/(?:u/[0-9]+/)?d/([A-Za-z0-9_-]{1,256})(?:/(?:edit|view|preview|copy|export|htmlview|present))?/?");
    private final TenantAccessResolver tenants;
    private final GoogleDriveConnectionService connections;
    private final JdbcGoogleDriveSourceRepository drive;
    private final GoogleDriveLinkReader linkReader;
    private final JdbcSourceRepository sources;
    private final JdbcSourceSyncRepository sync;
    private final JdbcIndexAttemptRepository indexing;
    private final JdbcSourceDocumentRepository documents;
    private final TransactionTemplate transactions;
    private final JdbcGoogleDriveSelectionRepository selections;
    private final JdbcGoogleDriveCredentialRepository credentials;
    private final GoogleDriveSelectionPolicy policy;

    public DefaultGoogleDriveSourceService(TenantAccessResolver tenants, GoogleDriveConnectionService connections,
            JdbcGoogleDriveSourceRepository drive, JdbcSourceRepository sources, JdbcSourceSyncRepository sync,
            JdbcIndexAttemptRepository indexing, JdbcSourceDocumentRepository documents,
            GoogleDriveLinkReader linkReader, PlatformTransactionManager transactionManager,
            JdbcGoogleDriveSelectionRepository selections, JdbcGoogleDriveCredentialRepository credentials,
            GoogleDriveSelectionPolicy policy) {
        this.tenants = tenants;
        this.connections = connections;
        this.drive = drive;
        this.linkReader = linkReader;
        this.sources = sources;
        this.sync = sync;
        this.indexing = indexing;
        this.documents = documents;
        this.transactions = new TransactionTemplate(transactionManager);
        this.selections = selections;
        this.credentials = credentials;
        this.policy = policy;
    }

    @Override
    public SelectionReceipt create(ActorId actor, UUID requestId, String name, CredentialId credentialId, ScopeMode scopeMode, List<String> links) {
        if (name == null || name.isBlank() || name.strip().length() > 120)
            throw SourceException.invalid("Source name must contain 1 to 120 characters.", "invalid source name");
        Objects.requireNonNull(credentialId, "credentialId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        var tenant = owner(actor);
        var ids = rootIds(scopeMode, links);
        policy.requireSize(name, links, List.of());
        String hash = requestHash("CREATE", name, credentialId.toString(), scopeMode.name(), links, List.of());
        return Objects.requireNonNull(transactions.execute(_ -> {
            if (!sources.lockActiveTenant(tenant) || !tenant.equals(owner(actor))) throw SourceException.notOwner();
            var receipt = selections.receipt(tenant, actor, requestId, hash);
            if (receipt.isPresent()) return receipt.get();
            var credential = credentials.lock(tenant, credentialId).orElseThrow(SourceException::notFound);
            if (!credential.usable()) throw SourceException.conflict("Google connection is unavailable");
            if (!selections.lockOwner(tenant, actor)) throw SourceException.notOwner();
            return selections.submit(tenant, actor, requestId, hash, new SourceId(UUID.randomUUID()), credentialId,
                    credential.revision(), 0, 0, scopeMode, name.strip(), ids, List.of(), policy.value());
        }));
    }

    @Override
    public Configuration configuration(ActorId actor, SourceId source) {
        var tenant = owner(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sources.lock(tenant, source);
            if (!tenant.equals(owner(actor, source))) throw SourceException.notOwner();
            return configuration(tenant, source);
        }));
    }

    private Configuration configuration(TenantId tenant, SourceId source) {
        var state = connections.state(tenant, source);
        var config = drive.configuration(tenant, source);
        return new Configuration(source, state.credentialId(), state.accountEmail(), state.status(), state.credentialRevision(),
                state.oauthClientConfigured(), config.revision(), config.syncIntervalMinutes(), config.scheduleRevision(),
                config.scopeMode(), drive.counts(tenant, source),
                config.scopeMode() == ScopeMode.GENERAL ? 0 : config.discoveryRevision(),
                config.scopeMode() == ScopeMode.GENERAL ? null : config.discoveredAt(),
                config.scopeMode() == ScopeMode.GENERAL ? List.of() : drive.discoveryErrors(tenant, source),
                config.lastSyncedAt(), config.pending(), config.errorCode(), selections.pending(tenant, source));
    }


    @Override
    public SelectionReceipt replaceRoots(ActorId actor, UUID requestId, SourceId source, long expectedRevision,
            long expectedDiscoveryRevision, long expectedCredentialRevision, ScopeMode scopeMode,
            List<String> links, List<String> linkedDocumentIds) {
        var tenant = owner(actor, source);
        Objects.requireNonNull(requestId, "requestId must not be null");
        var roots = rootIds(scopeMode, links);
        var approved = linkedIds(scopeMode, linkedDocumentIds);
        policy.requireSize("", links, linkedDocumentIds);
        String hash = requestHash("REPLACE", source.toString(),
                expectedRevision + ":" + expectedDiscoveryRevision + ":" + expectedCredentialRevision,
                scopeMode.name(), links, linkedDocumentIds);
        return Objects.requireNonNull(transactions.execute(_ -> {
            if (!sources.lockActiveTenant(tenant) || !tenant.equals(owner(actor, source))) throw SourceException.notOwner();
            var receipt = selections.receipt(tenant, actor, requestId, hash);
            if (receipt.isPresent()) return receipt.get();
            var saved = snapshot(actor, tenant, source, expectedRevision);
            if (saved.configuration().discoveryRevision() != expectedDiscoveryRevision
                    || saved.credentialRevision() != expectedCredentialRevision) throw SourceException.staleConfiguration();
            if (!selections.lockOwner(tenant, actor)) throw SourceException.notOwner();
            if (saved.configuration().scopeMode() != scopeMode)
                throw SourceException.invalid("Google Drive scope mode is chosen when the Source is created and cannot be changed.",
                        "attempt to change creation-only Drive scope mode");
            var candidates = new java.util.HashMap<String, LinkedDocument>();
            saved.documents().forEach(document -> candidates.put(document.id(), document));
            var approvals = new ArrayList<LinkedDocument>();
            for (String id : approved) {
                var candidate = candidates.get(id);
                if (candidate == null || !candidate.selected() && (candidate.origins().isEmpty()
                        || candidate.status() != LinkedDocumentStatus.AVAILABLE
                        || saved.configuration().discoveryScopeRevision() != expectedRevision
                        || saved.configuration().discoveryCredentialRevision() != saved.credentialRevision()))
                    throw invalidLinkedSelection();
                approvals.add(candidate);
            }
            return selections.submit(tenant, actor, requestId, hash, source, saved.credentialId(), saved.credentialRevision(),
                    expectedRevision, saved.configuration().discoveryRevision(), scopeMode, null, roots, approvals, policy.value());
        }));
    }

    @Override
    public Configuration discoverLinkedDocuments(ActorId actor, SourceId source, long expectedRevision) {
        var tenant = owner(actor, source);
        var saved = snapshot(actor, tenant, source, expectedRevision);
        if (saved.configuration().scopeMode() != ScopeMode.SPECIFIC)
            throw SourceException.invalid("Linked document discovery requires Specific scope.", "discovery requested for General scope");
        GoogleDriveLinkedDiscovery.Result result;
        var connection = connections.open(tenant, source);
        long credentialRevision = connection.credentialRevision();
        try (connection) {
            requireSnapshot(actor, tenant, source, saved, credentialRevision);
            result = new GoogleDriveLinkedDiscovery(connection.session(), linkReader).discover(saved.roots(), saved.documents());
        } catch (GoogleDriveProviderException exception) {
            authenticationFailed(tenant, source, credentialRevision, exception);
            throw exception;
        }
        return Objects.requireNonNull(transactions.execute(_ -> {
            checkSnapshot(actor, tenant, source, saved, credentialRevision);
            drive.saveDiscovery(tenant, source, expectedRevision, credentialRevision,
                    saved.configuration().discoveryRevision(), result.documents(), result.errors());
            return configuration(tenant, source);
        }));
    }

    private Snapshot snapshot(ActorId actor, TenantId tenant, SourceId source, long expectedRevision) {
        return Objects.requireNonNull(transactions.execute(_ -> {
            credentials.lockSource(tenant, source).orElseThrow(SourceException::notFound);
            sources.lock(tenant, source);
            var state = connections.state(tenant, source);
            if (!connections.current(tenant, source, state.credentialRevision())) throw SourceException.conflict("Google connection is unavailable");
            if (!tenant.equals(owner(actor, source))) throw SourceException.notOwner();
            var config = drive.configuration(tenant, source);
            if (config.revision() != expectedRevision) throw SourceException.staleConfiguration();
            return new Snapshot(config, state.credentialId(), state.credentialRevision(),
                    drive.roots(tenant, source), drive.linkedDocuments(tenant, source));
        }));
    }

    private void requireSnapshot(ActorId actor, TenantId tenant, SourceId source, Snapshot saved, long credentialRevision) {
        transactions.executeWithoutResult(_ -> checkSnapshot(actor, tenant, source, saved, credentialRevision));
    }

    private void checkSnapshot(ActorId actor, TenantId tenant, SourceId source, Snapshot saved, long credentialRevision) {
        credentials.lockSource(tenant, source).orElseThrow(SourceException::notFound);
        sources.lock(tenant, source);
        if (credentialRevision != saved.credentialRevision() || !connections.current(tenant, source, credentialRevision)
                || !connections.state(tenant, source).credentialId().equals(saved.credentialId()))
            throw SourceException.staleConfiguration();
        if (!tenant.equals(owner(actor, source))) throw SourceException.notOwner();
        var config = drive.configuration(tenant, source);
        if (config.revision() != saved.configuration().revision()
                || config.discoveryRevision() != saved.configuration().discoveryRevision()) throw SourceException.staleConfiguration();
    }

    private void authenticationFailed(TenantId tenant, SourceId source, long revision, GoogleDriveProviderException exception) {
        if (exception.failure() == GoogleDriveProviderException.Failure.AUTHENTICATION)
            connections.authenticationFailed(tenant, source, revision);
    }

    private Set<String> linkedIds(ScopeMode scopeMode, List<String> ids) {
        if (ids == null || ids.size() > policy.value().maxLinkedDocuments() || scopeMode == ScopeMode.GENERAL && !ids.isEmpty()) throw invalidLinkedSelection();
        var distinct = new HashSet<String>();
        for (String id : ids) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,256}") || "root".equals(id) || !distinct.add(id))
                throw invalidLinkedSelection();
        }
        return distinct;
    }

    private static SourceException invalidLinkedSelection() {
        return SourceException.invalid("Select only available documents from the current linked document discovery.",
                "invalid or unauthorized Google Drive linked document approval");
    }

    private record Snapshot(JdbcGoogleDriveSourceRepository.ConfigurationRow configuration, CredentialId credentialId,
            long credentialRevision, List<Root> roots, List<LinkedDocument> documents) {}

    @Override
    public Configuration updateSchedule(ActorId actor, SourceId source, long expectedRevision, int syncIntervalMinutes) {
        var tenant = owner(actor, source);
        if (syncIntervalMinutes < 1) {
            throw SourceException.invalid("Automatic interval must be at least 1 minute.", "invalid Drive sync interval");
        }
        return Objects.requireNonNull(transactions.execute(_ -> {
            sources.lock(tenant, source);
            if (!tenant.equals(owner(actor, source))) throw SourceException.notOwner();
            drive.updateSchedule(tenant, source, expectedRevision, syncIntervalMinutes);
            return configuration(tenant, source);
        }));
    }

    @Override
    public SourceOperationView synchronize(ActorId actor, SourceId source) {
        var tenant = owner(actor, source);
        return java.util.Objects.requireNonNull(transactions.execute(_ -> {
            if (!tenant.equals(owner(actor, source))) throw SourceException.notOwner();
            sources.lock(tenant, source);
            var state = connections.state(tenant, source);
            if (!connections.current(tenant, source, state.credentialRevision())) throw SourceException.conflict("Google connection is unavailable");
            if (drive.roots(tenant, source).isEmpty()) throw SourceException.invalid("Select roots before synchronizing.", "Drive roots not configured");
            return sync.enqueue(tenant, source, state.credentialRevision(), SourceRunTrigger.MANUAL, actor);
        }));
    }

    private TenantId owner(ActorId actor, SourceId source) {
        var tenant = owner(actor);
        drive.requireGoogle(tenant, source);
        return tenant;
    }

    private TenantId owner(ActorId actor) {
        return tenants.findActiveOwnerTenant(actor).orElseThrow(SourceException::notOwner);
    }

    private List<String> rootIds(ScopeMode scopeMode, List<String> links) {
        if (scopeMode == null) {
            throw SourceException.invalid("Choose General or Specific scope.", "missing Drive scope mode");
        }
        if (scopeMode == ScopeMode.GENERAL) {
            if (links == null || !links.isEmpty()) {
                throw SourceException.invalid("General scope requires an empty links array.", "invalid General Drive links");
            }
            return List.of();
        }
        if (links == null || links.isEmpty() || links.size() > policy.value().maxExplicitRootsPerSource()) {
            throw SourceException.invalid("Provide between 1 and " + policy.value().maxExplicitRootsPerSource() + " file or folder links.", "invalid root count");
        }
        var ids = links.stream().map(DefaultGoogleDriveSourceService::fileId).toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw SourceException.invalidRootLink("Each link must identify a different file or folder.");
        }
        return ids;
    }


    static String fileId(String link) {
        if (link == null || link.length() > 2048) throw invalidLink();
        try {
            URI uri = URI.create(link.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw invalidLink();
            boolean drive = "drive.google.com".equalsIgnoreCase(uri.getHost());
            boolean docs = "docs.google.com".equalsIgnoreCase(uri.getHost());
            if (!drive && !docs) throw invalidLink();
            var match = (drive ? DRIVE_PATH : DOCUMENT_PATH).matcher(uri.getRawPath());
            String id;
            if (match.matches()) {
                id = match.group(1);
            } else if (drive && "/open".equals(uri.getRawPath()) && uri.getRawQuery() != null) {
                id = null;
                for (String parameter : uri.getRawQuery().split("&")) {
                    String[] pair = parameter.split("=", 2);
                    if ("id".equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                        if (id != null || pair.length != 2) throw invalidLink();
                        id = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                }
            } else {
                throw invalidLink();
            }
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,256}") || "root".equals(id)) throw invalidLink();
            return id;
        } catch (IllegalArgumentException exception) {
            throw invalidLink();
        }
    }

    private static SourceException invalidLink() {
        return SourceException.invalidRootLink("Paste an HTTPS Google Drive file or folder link.");
    }


    @Override
    public SelectionPolicy selectionPolicy(ActorId actor) { owner(actor); return policy.value(); }

    @Override
    public int selectionRequestByteLimit() { return policy.value().maxRequestBytes(); }

    @Override
    public SelectionReceipt selectionRequest(ActorId actor, UUID requestId) {
        return selections.receipt(owner(actor), actor, requestId, null).orElseThrow(SourceException::notFound);
    }

    @Override
    public SelectionDraft selectionDraft(ActorId actor, SourceId source) {
        var tenant = owner(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sources.lock(tenant, source);
            var config = drive.configuration(tenant, source);
            var state = connections.state(tenant, source);
            return new SelectionDraft(config.revision(), config.discoveryRevision(), state.credentialRevision(),
                    config.scopeMode() == ScopeMode.GENERAL ? List.of() : drive.roots(tenant, source).stream()
                            .map(root -> "https://drive.google.com/file/d/" + root.id() + "/view").toList(),
                    drive.approvedIds(tenant, source));
        }));
    }

    @Override
    public SelectionPage selection(ActorId actor, SourceId source, @Nullable String search,
            @Nullable SelectionKind kind, @Nullable String cursor, int size) {
        var tenant = owner(actor, source);
        return Objects.requireNonNull(transactions.execute(_ -> {
            sources.lock(tenant, source);
            var state = connections.state(tenant, source);
            return drive.selection(tenant, source, state.credentialRevision(), search, kind, cursor, size);
        }));
    }

    @Override
    public SelectionTreePage selectionTree(ActorId actor, SourceId source, @Nullable String parentId,
            @Nullable String cursor, int size) {
        var tenant = owner(actor, source);
        GoogleDriveSelectionTree.validate(parentId, cursor, size);
        var saved = Objects.requireNonNull(transactions.execute(_ -> {
            var credential = credentials.lockSource(tenant, source).orElseThrow(SourceException::notFound);
            sources.lock(tenant, source);
            if (!tenant.equals(owner(actor, source))) throw SourceException.notOwner();
            return new TreeSnapshot(drive.configuration(tenant, source), connections.state(tenant, source),
                    drive.roots(tenant, source), credential.usable());
        }));
        var tree = new GoogleDriveSelectionTree(drive, tenant, source, saved.configuration(), saved.state(),
                saved.roots(), saved.usable(), parentId, cursor, size);
        GoogleDriveSelectionTree.Page page;
        if (tree.requiresProvider()) {
            try (var connection = connections.open(tenant, source)) {
                transactions.executeWithoutResult(_ -> checkTreeSnapshot(actor, tenant, source, saved, connection.credentialRevision()));
                page = tree.read(connection.session());
            } catch (GoogleDriveProviderException exception) {
                transactions.executeWithoutResult(_ -> checkTreeSnapshot(actor, tenant, source, saved, saved.state().credentialRevision()));
                throw exception;
            }
        } else {
            page = tree.read(null);
        }
        var result = page;
        return Objects.requireNonNull(transactions.execute(_ -> {
            checkTreeSnapshot(actor, tenant, source, saved, saved.state().credentialRevision());
            return new SelectionTreePage(saved.configuration().revision(), saved.configuration().discoveryRevision(),
                    saved.state().credentialRevision(), result.items(), result.nextCursor(), drive.counts(tenant, source));
        }));
    }

    private void checkTreeSnapshot(ActorId actor, TenantId tenant, SourceId source, TreeSnapshot saved, long credentialRevision) {
        var credential = credentials.lockSource(tenant, source).orElseThrow(SourceException::notFound);
        sources.lock(tenant, source);
        if (!tenant.equals(owner(actor, source))) throw SourceException.notOwner();
        var state = connections.state(tenant, source);
        var config = drive.configuration(tenant, source);
        if (credentialRevision != saved.state().credentialRevision() || !state.equals(saved.state())
                || credential.usable() != saved.usable() || config.scopeMode() != saved.configuration().scopeMode()
                || config.revision() != saved.configuration().revision()
                || config.discoveryRevision() != saved.configuration().discoveryRevision()
                || config.discoveryScopeRevision() != saved.configuration().discoveryScopeRevision()
                || config.discoveryCredentialRevision() != saved.configuration().discoveryCredentialRevision())
            throw SourceException.staleConfiguration();
    }

    private record TreeSnapshot(JdbcGoogleDriveSourceRepository.ConfigurationRow configuration,
            GoogleDriveConnectionService.State state, List<Root> roots, boolean usable) {}

    void requireIntent(Work work, JdbcGoogleDriveSelectionRepository.Intent intent) {
        var tenant = work.tenantId();
        if (intent.credentialId() == null || !sources.lockActiveTenant(tenant) || !tenant.equals(owner(intent.actorId())))
            throw SourceException.staleConfiguration();
        var credential = credentials.lock(tenant, new CredentialId(intent.credentialId())).orElseThrow(SourceException::staleConfiguration);
        if (!credential.usable() || credential.revision() != intent.credentialRevision()) throw SourceException.staleConfiguration();
        if (intent.name() == null) {
            sources.lock(tenant, work.sourceId());
            drive.requireGoogle(tenant, work.sourceId());
            var config = drive.configuration(tenant, work.sourceId());
            if (config.revision() != intent.scopeRevision() || config.discoveryRevision() != intent.discoveryRevision()
                    || config.scopeMode() != intent.scopeMode()
                    || !connections.state(tenant, work.sourceId()).credentialId().value().equals(intent.credentialId()))
                throw SourceException.staleConfiguration();
        }
        if (!selections.lockOwner(tenant, intent.actorId())) throw SourceException.notOwner();
        if (!selections.current(work)) throw SourceException.staleConfiguration();
    }

    void activate(Work work, JdbcGoogleDriveSelectionRepository.Intent intent, List<Root> roots, List<LinkedDocument> approvals) {
        requireIntent(work, intent);
        var tenant = work.tenantId();
        var source = work.sourceId();
        if (intent.name() != null) {
            drive.create(tenant, source, intent.name(), new CredentialId(Objects.requireNonNull(intent.credentialId())), intent.scopeMode(), roots);
            sync.enqueue(tenant, source, intent.credentialRevision(), SourceRunTrigger.INITIAL, intent.actorId());
        } else {
            boolean changed = !Set.copyOf(drive.roots(tenant, source).stream().map(Root::id).toList())
                    .equals(Set.copyOf(roots.stream().map(Root::id).toList()));
            sync.cancel(tenant, source);
            drive.replace(tenant, source, intent.scopeRevision(), intent.scopeMode(), roots);
            drive.replaceApprovals(tenant, source, approvals.stream().map(LinkedDocument::id).toList(), approvals, changed);
            indexing.cancelForSource(tenant, source);
            documents.invalidateSource(tenant, source);
            sources.recomputeStatus(tenant, source, false);
        }
        selections.finish(work, "SUCCEEDED", null);
    }

    private static String requestHash(String action, String target, String authority, String mode, List<String> roots, List<String> approvals) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(action, target, authority, mode)) hashPart(digest, value);
            hashPart(digest, Integer.toString(roots.size()));
            roots.forEach(value -> hashPart(digest, value));
            hashPart(digest, Integer.toString(approvals.size()));
            approvals.forEach(value -> hashPart(digest, value));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static void hashPart(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte)(bytes.length >>> 24));
        digest.update((byte)(bytes.length >>> 16));
        digest.update((byte)(bytes.length >>> 8));
        digest.update((byte)bytes.length);
        digest.update(bytes);
    }
}
