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
import io.memoryos.organization.OrganizationAccessResolver;
import io.memoryos.organization.OrganizationId;

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
    private final JdbcSourceDocumentRepository documents;
    private final JdbcSourceQueryRepository queries;
    private final OrganizationAccessResolver organizationAccessResolver;

    public DefaultSourceManagementService(
            JdbcSourceRepository sources,
            JdbcSourceItemRepository items,
            JdbcIndexAttemptRepository attempts,
            JdbcSourceDocumentRepository documents,
            JdbcSourceQueryRepository queries,
            OrganizationAccessResolver organizationAccessResolver
    ) {
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.items = Objects.requireNonNull(items, "items must not be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
        this.queries = Objects.requireNonNull(queries, "queries must not be null");
        this.organizationAccessResolver = Objects.requireNonNull(
                organizationAccessResolver,
                "organizationAccessResolver must not be null"
        );
    }

    @Override
    @Transactional
    public SourceDetail createFileSource(ActorId actorId, String name) {
        OrganizationId organizationId = requireOwner(actorId);
        var pair = sources.createFileSource(organizationId, requireName(name));
        return queries.detail(organizationId, pair.sourceId());
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
    public List<SourceOperationView> listIndexOperations(ActorId actorId, SourceId sourceId) {
        OrganizationId organizationId = requireOwner(actorId);
        queries.detail(organizationId, requireSourceId(sourceId));
        return attempts.list(organizationId, sourceId);
    }

    @Override
    @Transactional
    public SourceUploadResult upload(
            ActorId actorId,
            SourceId sourceId,
            String filename,
            byte[] content,
            String sha256
    ) {
        OrganizationId organizationId = requireOwner(actorId);
        requireContent(content);
        var pair = mutable(sources.lock(organizationId, requireSourceId(sourceId)));
        var version = items.resolveOrCreate(
                organizationId,
                pair,
                requireFilename(filename),
                content,
                requireSha256(sha256)
        );
        SourceOperationView operation = attempts.findLive(organizationId, sourceId, version)
                .orElseGet(() -> attempts.create(organizationId, pair, version));
        return new SourceUploadResult(queries.item(organizationId, sourceId, version.itemId()), operation);
    }

    @Override
    @Transactional
    public SourceOperationView reindex(ActorId actorId, SourceId sourceId, SourceItemId itemId) {
        OrganizationId organizationId = requireOwner(actorId);
        var pair = mutable(sources.lock(organizationId, requireSourceId(sourceId)));
        var version = items.currentVersion(
                organizationId,
                pair,
                Objects.requireNonNull(itemId, "itemId must not be null")
        );
        return attempts.findLive(organizationId, sourceId, version)
                .orElseGet(() -> attempts.create(organizationId, pair, version));
    }

    @Override
    @Transactional
    public SourceOperationView removeItem(ActorId actorId, SourceId sourceId, SourceItemId itemId) {
        OrganizationId organizationId = requireOwner(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        SourceItemId requiredItemId = Objects.requireNonNull(itemId, "itemId must not be null");
        var parentCleanup = sources.findCleanup(
                organizationId,
                SourceOperationType.DELETE_SOURCE,
                "PAIR:" + requiredSourceId.value()
        );
        if (parentCleanup.isPresent()) {
            return parentCleanup.get();
        }
        String targetKey = "ITEM:" + requiredItemId.value();
        var existing = sources.findCleanup(organizationId, SourceOperationType.REMOVE_ITEM, targetKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        var pair = sources.lock(organizationId, requiredSourceId);
        parentCleanup = sources.findCleanup(
                organizationId,
                SourceOperationType.DELETE_SOURCE,
                "PAIR:" + requiredSourceId.value()
        );
        if (parentCleanup.isPresent()) {
            return parentCleanup.get();
        }
        existing = sources.findCleanup(organizationId, SourceOperationType.REMOVE_ITEM, targetKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        var mutablePair = mutable(pair);
        items.currentVersion(organizationId, mutablePair, requiredItemId);
        items.markDeleting(organizationId, mutablePair, requiredItemId);
        documents.invalidateItem(organizationId, requiredSourceId, requiredItemId);
        attempts.cancelForItem(organizationId, requiredSourceId, requiredItemId);
        return sources.createCleanup(
                new SourceOperationId(UUID.randomUUID()),
                organizationId,
                SourceOperationType.REMOVE_ITEM,
                targetKey,
                requiredSourceId,
                requiredItemId
        );
    }

    @Override
    @Transactional
    public SourceOperationView deleteSource(ActorId actorId, SourceId sourceId) {
        OrganizationId organizationId = requireOwner(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        String targetKey = "PAIR:" + requiredSourceId.value();
        var existing = sources.findCleanup(organizationId, SourceOperationType.DELETE_SOURCE, targetKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        var pair = sources.lock(organizationId, requiredSourceId);
        existing = sources.findCleanup(organizationId, SourceOperationType.DELETE_SOURCE, targetKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        sources.markDeleting(organizationId, pair);
        documents.invalidateSource(organizationId, requiredSourceId);
        attempts.cancelForSource(organizationId, requiredSourceId);
        sources.supersedeItemCleanups(organizationId, requiredSourceId);
        return sources.createCleanup(
                new SourceOperationId(UUID.randomUUID()),
                organizationId,
                SourceOperationType.DELETE_SOURCE,
                targetKey,
                requiredSourceId,
                null
        );
    }

    @Override
    @Transactional(readOnly = true)
    public SourceOperationView getOperation(ActorId actorId, SourceOperationId operationId) {
        OrganizationId organizationId = requireOwner(actorId);
        SourceOperationId requiredOperationId = Objects.requireNonNull(operationId, "operationId must not be null");
        return attempts.findById(organizationId, requiredOperationId)
                .or(() -> sources.findCleanupById(organizationId, requiredOperationId))
                .orElseThrow(SourceException::notFound);
    }

    private OrganizationId requireOwner(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        return organizationAccessResolver.findActiveOwnerOrganization(actorId)
                .orElseThrow(SourceException::notOwner);
    }

    private static JdbcSourceRepository.SourcePair mutable(JdbcSourceRepository.SourcePair pair) {
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

    private static String requireSha256(String sha256) {
        Objects.requireNonNull(sha256, "sha256 must not be null");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be lowercase hexadecimal");
        }
        return sha256;
    }
}
