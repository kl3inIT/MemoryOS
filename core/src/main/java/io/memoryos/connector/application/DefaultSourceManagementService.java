package io.memoryos.connector.application;

import io.memoryos.connector.SourceDetail;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceStatus;
import io.memoryos.connector.SourceSummary;
import io.memoryos.connector.SourceUploadResult;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.persistence.JdbcSourceQueryRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.identity.ActorId;
import io.memoryos.tenant.TenantAccessResolver;
import io.memoryos.tenant.TenantId;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultSourceManagementService implements SourceManagementService {

    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    private final JdbcSourceRepository sources;
    private final JdbcSourceItemRepository items;
    private final JdbcIndexAttemptRepository attempts;
    private final JdbcSourceDocumentRepository sourceDocuments;
    private final JdbcSourceQueryRepository queries;
    private final TenantAccessResolver tenantAccess;

    public DefaultSourceManagementService(
            JdbcSourceRepository sources,
            JdbcSourceItemRepository items,
            JdbcIndexAttemptRepository attempts,
            JdbcSourceDocumentRepository sourceDocuments,
            JdbcSourceQueryRepository queries,
            TenantAccessResolver tenantAccess
    ) {
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.items = Objects.requireNonNull(items, "items must not be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.sourceDocuments = Objects.requireNonNull(sourceDocuments, "sourceDocuments must not be null");
        this.queries = Objects.requireNonNull(queries, "queries must not be null");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess must not be null");
    }

    @Override
    @Transactional
    public SourceDetail createFileSource(ActorId actorId, String name) {
        TenantId tenantId = requireOwner(actorId);
        var pair = sources.createFileSource(tenantId, requireName(name));
        return queries.detail(tenantId, pair.sourceId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceSummary> listSources(ActorId actorId) {
        return queries.list(requireOwner(actorId));
    }

    @Override
    @Transactional(readOnly = true)
    public SourceDetail getSource(ActorId actorId, SourceId sourceId) {
        return queries.detail(requireOwner(actorId), requireSourceId(sourceId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceOperationView> listIndexAttempts(ActorId actorId, SourceId sourceId, int limit) {
        TenantId tenantId = requireOwner(actorId);
        queries.summary(tenantId, requireSourceId(sourceId));
        return attempts.list(tenantId, sourceId, limit);
    }

    @Override
    @Transactional
    public SourceUploadResult upload(ActorId actorId, SourceId sourceId, String filename, byte[] content) {
        TenantId tenantId = requireOwner(actorId);
        requireContent(content);
        var pair = requireMutable(sources.lock(tenantId, requireSourceId(sourceId)));
        var version = items.resolveOrCreate(
                tenantId,
                pair,
                requireFilename(filename),
                content,
                sha256(content)
        );
        SourceOperationView operation = attempts.findLive(tenantId, sourceId, version)
                .orElseGet(() -> attempts.create(tenantId, pair, version));
        return new SourceUploadResult(queries.item(tenantId, sourceId, version.itemId()), operation);
    }

    @Override
    @Transactional
    public SourceOperationView reindex(ActorId actorId, SourceId sourceId, SourceItemId itemId) {
        TenantId tenantId = requireOwner(actorId);
        var pair = requireMutable(sources.lock(tenantId, requireSourceId(sourceId)));
        var version = items.lockCurrentVersion(
                tenantId,
                pair,
                Objects.requireNonNull(itemId, "itemId must not be null")
        );
        return attempts.findLive(tenantId, sourceId, version)
                .orElseGet(() -> attempts.create(tenantId, pair, version));
    }

    @Override
    @Transactional
    public SourceOperationView removeItem(ActorId actorId, SourceId sourceId, SourceItemId itemId) {
        TenantId tenantId = requireOwner(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        SourceItemId requiredItemId = Objects.requireNonNull(itemId, "itemId must not be null");
        var parentCleanup = sources.findCleanup(
                tenantId,
                SourceOperationType.DELETE_SOURCE,
                "PAIR:" + requiredSourceId.value()
        );
        if (parentCleanup.isPresent()) {
            return parentCleanup.get();
        }
        String targetKey = "ITEM:" + requiredItemId.value();
        var existing = sources.findCleanup(tenantId, SourceOperationType.REMOVE_ITEM, targetKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        var pair = sources.lock(tenantId, requiredSourceId);
        parentCleanup = sources.findCleanup(
                tenantId,
                SourceOperationType.DELETE_SOURCE,
                "PAIR:" + requiredSourceId.value()
        );
        if (parentCleanup.isPresent()) {
            return parentCleanup.get();
        }
        existing = sources.findCleanup(tenantId, SourceOperationType.REMOVE_ITEM, targetKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        var mutablePair = requireMutable(pair);
        items.lockCurrentVersion(tenantId, mutablePair, requiredItemId);
        items.markDeleting(tenantId, mutablePair, requiredItemId);
        sourceDocuments.invalidateItem(tenantId, requiredSourceId, requiredItemId);
        attempts.cancelForItem(tenantId, requiredSourceId, requiredItemId);
        return sources.createCleanup(
                new SourceOperationId(UUID.randomUUID()),
                tenantId,
                SourceOperationType.REMOVE_ITEM,
                targetKey,
                requiredSourceId,
                requiredItemId
        );
    }

    @Override
    @Transactional
    public SourceOperationView deleteSource(ActorId actorId, SourceId sourceId) {
        TenantId tenantId = requireOwner(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        String targetKey = "PAIR:" + requiredSourceId.value();
        var existing = sources.findCleanup(tenantId, SourceOperationType.DELETE_SOURCE, targetKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        var pair = sources.lock(tenantId, requiredSourceId);
        existing = sources.findCleanup(tenantId, SourceOperationType.DELETE_SOURCE, targetKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        sources.markDeleting(tenantId, pair);
        sourceDocuments.invalidateSource(tenantId, requiredSourceId);
        attempts.cancelForSource(tenantId, requiredSourceId);
        sources.supersedeItemCleanups(tenantId, requiredSourceId);
        return sources.createCleanup(
                new SourceOperationId(UUID.randomUUID()),
                tenantId,
                SourceOperationType.DELETE_SOURCE,
                targetKey,
                requiredSourceId,
                null
        );
    }

    @Override
    @Transactional(readOnly = true)
    public SourceOperationView getOperation(ActorId actorId, SourceOperationId operationId) {
        TenantId tenantId = requireOwner(actorId);
        SourceOperationId requiredOperationId = Objects.requireNonNull(operationId, "operationId must not be null");
        return attempts.findById(tenantId, requiredOperationId)
                .or(() -> sources.findCleanupById(tenantId, requiredOperationId))
                .orElseThrow(SourceException::notFound);
    }

    private TenantId requireOwner(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        return tenantAccess.findActiveOwnerTenant(actorId)
                .orElseThrow(SourceException::notOwner);
    }

    private static JdbcSourceRepository.SourcePair requireMutable(JdbcSourceRepository.SourcePair pair) {
        if (pair.status() == SourceStatus.DELETING) {
            throw SourceException.conflict("source is deleting");
        }
        return pair;
    }

    private static SourceId requireSourceId(SourceId sourceId) {
        return Objects.requireNonNull(sourceId, "sourceId must not be null");
    }

    private static void requireContent(byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        if (content.length == 0 || content.length > MAX_FILE_BYTES) {
            throw SourceException.invalid(
                    "Upload one file between 1 byte and 10 MiB.",
                    "uploaded content was empty or exceeded 10 MiB"
            );
        }
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String normalized = name.strip();
        if (normalized.isEmpty() || normalized.length() > 120) {
            throw SourceException.invalid(
                    "Enter a source name between 1 and 120 characters.",
                    "source name was blank or exceeded 120 characters"
            );
        }
        return normalized;
    }

    private static String requireFilename(String filename) {
        Objects.requireNonNull(filename, "filename must not be null");
        String normalized = filename.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw SourceException.invalid(
                    "The uploaded filename is invalid.",
                    "normalized filename was blank or exceeded 255 characters"
            );
        }
        return normalized;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
