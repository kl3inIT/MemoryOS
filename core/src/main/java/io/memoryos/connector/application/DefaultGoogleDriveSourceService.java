package io.memoryos.connector.application;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveConnectionService;
import io.memoryos.connector.GoogleDriveProvider;
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
import io.memoryos.identity.ActorId;
import io.memoryos.tenant.TenantAccessResolver;
import io.memoryos.tenant.TenantId;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
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
    private final JdbcSourceRepository sources;
    private final JdbcSourceSyncRepository sync;
    private final JdbcIndexAttemptRepository indexing;
    private final JdbcSourceDocumentRepository documents;
    private final TransactionTemplate transactions;

    public DefaultGoogleDriveSourceService(TenantAccessResolver tenants, GoogleDriveConnectionService connections,
            JdbcGoogleDriveSourceRepository drive, JdbcSourceRepository sources, JdbcSourceSyncRepository sync,
            JdbcIndexAttemptRepository indexing, JdbcSourceDocumentRepository documents,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.connections = connections;
        this.drive = drive;
        this.sources = sources;
        this.sync = sync;
        this.indexing = indexing;
        this.documents = documents;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public SourceId create(ActorId actor, String name, CredentialId credentialId, ScopeMode scopeMode, List<String> links) {
        if (name == null || name.isBlank() || name.strip().length() > 120) {
            throw SourceException.invalid("Source name must contain 1 to 120 characters.", "invalid source name");
        }
        Objects.requireNonNull(credentialId, "credentialId must not be null");
        var tenant = owner(actor);
        var ids = rootIds(scopeMode, links);
        var connection = connections.openCredential(tenant, credentialId);
        long revision = connection.credentialRevision();
        List<Root> roots;
        try (connection) {
            roots = validateRoots(connection.session(), scopeMode, ids);
        } catch (GoogleDriveProviderException exception) {
            if (exception.failure() == GoogleDriveProviderException.Failure.AUTHENTICATION)
                connections.authenticationFailedCredential(tenant, credentialId, revision);
            throw exception;
        }
        return Objects.requireNonNull(transactions.execute(_ -> {
            if (!tenant.equals(owner(actor))) throw SourceException.notOwner();
            if (!connections.currentCredential(tenant, credentialId, revision))
                throw SourceException.conflict("Google credential changed during root validation");
            var source = drive.create(tenant, name.strip(), credentialId, scopeMode, roots);
            sync.enqueue(tenant, source, revision);
            return source;
        }));
    }

    @Override
    public Configuration configuration(ActorId actor, SourceId source) {
        return configuration(owner(actor, source), source);
    }

    private Configuration configuration(TenantId tenant, SourceId source) {
        var state = connections.state(tenant, source);
        var config = drive.configuration(tenant, source);
        return new Configuration(source, state.credentialId(), state.accountEmail(), state.status(), state.credentialRevision(),
                state.oauthClientConfigured(), config.revision(), config.syncIntervalMinutes(), config.scheduleRevision(),
                config.scopeMode(), config.scopeMode() == ScopeMode.GENERAL ? List.of() : drive.roots(tenant, source),
                config.lastSyncedAt(), config.pending(), config.errorCode());
    }


    @Override
    public Configuration replaceRoots(ActorId actor, SourceId source, long expectedRevision, ScopeMode scopeMode, List<String> links) {
        var tenant = owner(actor, source);
        List<String> rootIds = rootIds(scopeMode, links);
        if (drive.configuration(tenant, source).revision() != expectedRevision) throw SourceException.staleConfiguration();
        List<Root> roots;
        var connection = connections.open(tenant, source);
        long credentialRevision = connection.credentialRevision();
        try (connection) {
            roots = validateRoots(connection.session(), scopeMode, rootIds);
        } catch (GoogleDriveProviderException exception) {
            if (exception.failure() == GoogleDriveProviderException.Failure.AUTHENTICATION)
                connections.authenticationFailed(tenant, source, credentialRevision);
            throw exception;
        }
        transactions.executeWithoutResult(_ -> {
            if (!tenant.equals(owner(actor, source))) throw SourceException.notOwner();
            sources.lock(tenant, source);
            if (!connections.current(tenant, source, credentialRevision)) throw SourceException.staleConfiguration();
            sync.cancel(tenant, source);
            drive.replace(tenant, source, expectedRevision, scopeMode, roots);
            indexing.cancelForSource(tenant, source);
            documents.invalidateSource(tenant, source);
            sources.recomputeStatus(tenant, source, false);
        });
        return configuration(tenant, source);
    }

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
            return sync.enqueue(tenant, source, state.credentialRevision());
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

    private static List<String> rootIds(ScopeMode scopeMode, List<String> links) {
        if (scopeMode == null) {
            throw SourceException.invalid("Choose General or Specific scope.", "missing Drive scope mode");
        }
        if (scopeMode == ScopeMode.GENERAL) {
            if (links == null || !links.isEmpty()) {
                throw SourceException.invalid("General scope requires an empty links array.", "invalid General Drive links");
            }
            return List.of();
        }
        if (links == null || links.isEmpty() || links.size() > 20) {
            throw SourceException.invalid("Provide between 1 and 20 file or folder links.", "invalid root count");
        }
        var ids = links.stream().map(DefaultGoogleDriveSourceService::fileId).toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw SourceException.invalidRootLink("Each link must identify a different file or folder.");
        }
        return ids;
    }

    private static List<Root> validateRoots(GoogleDriveProvider.Session session, ScopeMode scopeMode, List<String> rootIds) {
        if (scopeMode == ScopeMode.GENERAL) {
            var root = GoogleDriveRootValidation.resolveMyDriveRoot(session);
            return List.of(new Root(root.id(), root.name(), root.mimeType()));
        }
        List<Root> roots = new ArrayList<>();
        Set<String> selected = Set.copyOf(rootIds);
        int requests = 0;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
        for (String id : rootIds) {
            if (++requests > 256 || System.nanoTime() >= deadline)
                throw SourceException.invalid("The selected roots exceed the verification limit.", "root verification bound exceeded");
            var file = session.metadata(id);
            requireSupported(file);
            if (file.folder() && (file.id().equals(file.driveId())
                    || file.driveId() == null && file.parents().isEmpty()
                    && file.id().equals(session.metadata("root").id()))) {
                throw SourceException.invalidRootLink("Link a specific folder, not an entire drive.");
            }
            roots.add(new Root(file.id(), file.name(), file.mimeType()));
            var ancestors = new ArrayDeque<>(file.parents());
            var visited = new HashSet<String>();
            while (!ancestors.isEmpty()) {
                String parent = ancestors.removeFirst();
                if (selected.contains(parent)) throw SourceException.overlappingRoots();
                if (!visited.add(parent)) continue;
                if (++requests > 256 || System.nanoTime() >= deadline)
                    throw SourceException.invalid("The selected roots exceed the verification limit.", "root verification bound exceeded");
                var ancestor = session.metadata(parent);
                requireSupported(ancestor);
                ancestors.addAll(ancestor.parents());
            }
        }
        return roots;
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

    private static void requireSupported(GoogleDriveProvider.FileMetadata file) {
        if (file.trashed() || file.shortcutTargetId() != null
                || "application/vnd.google-apps.shortcut".equals(file.mimeType())) throw SourceException.unsupportedRoot();
    }
}
