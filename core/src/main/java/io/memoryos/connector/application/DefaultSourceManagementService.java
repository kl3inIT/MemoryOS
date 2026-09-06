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
import io.memoryos.connector.SourceUploadReceipt;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceGroupRepository;
import io.memoryos.connector.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.persistence.JdbcSourceOperationQueryRepository;
import io.memoryos.connector.persistence.JdbcSourceQueryRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.connector.persistence.JdbcSourceUploadRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.Authority;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupIdentity;
import io.memoryos.iam.GroupIdentityPage;
import io.memoryos.iam.GroupScopeService;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantId;
import io.memoryos.objectstorage.ObjectUploadAuthorization;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectUploadSpecification;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DefaultSourceManagementService implements SourceManagementService {

    private final JdbcSourceRepository sources;
    private final JdbcSourceItemRepository items;
    private final JdbcIndexAttemptRepository attempts;
    private final JdbcSourceDocumentRepository sourceDocuments;
    private final JdbcSourceQueryRepository queries;
    private final JdbcSourceOperationQueryRepository operationQueries;
    private final JdbcSourceGroupRepository sourceGroups;
    private final JdbcSourceUploadRepository sourceUploads;
    private final ObjectUploadService objectUploads;
    private final IamAuthorization authorization;
    private final GroupScopeService groupScopes;
    private final TransactionTemplate transactions;

    public DefaultSourceManagementService(
            JdbcSourceRepository sources,
            JdbcSourceItemRepository items,
            JdbcIndexAttemptRepository attempts,
            JdbcSourceDocumentRepository sourceDocuments,
            JdbcSourceQueryRepository queries,
            JdbcSourceOperationQueryRepository operationQueries,
            JdbcSourceGroupRepository sourceGroups,
            JdbcSourceUploadRepository sourceUploads,
            ObjectUploadService objectUploads,
            IamAuthorization authorization,
            GroupScopeService groupScopes,
            PlatformTransactionManager transactionManager
    ) {
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.items = Objects.requireNonNull(items, "items must not be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.sourceDocuments = Objects.requireNonNull(sourceDocuments, "sourceDocuments must not be null");
        this.queries = Objects.requireNonNull(queries, "queries must not be null");
        this.operationQueries = Objects.requireNonNull(operationQueries, "operationQueries must not be null");
        this.sourceGroups = Objects.requireNonNull(sourceGroups, "sourceGroups must not be null");
        this.sourceUploads = Objects.requireNonNull(sourceUploads, "sourceUploads must not be null");
        this.objectUploads = Objects.requireNonNull(objectUploads, "objectUploads must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
        this.groupScopes = Objects.requireNonNull(groupScopes, "groupScopes must not be null");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    @Override
    @Transactional
    public SourceDetail createFileSource(
            ActorId actorId,
            String name,
            Collection<GroupId> groupIds
    ) {
        ActorId requiredActorId = requireActorId(actorId);
        List<GroupId> requestedGroupIds = normalizeGroupIds(groupIds, true);
        String normalizedName = requireName(name);
        IamAccess access = authorization.lockAndRequireExclusive(
                requiredActorId,
                IamCapability.SOURCES_MANAGE
        );
        List<GroupId> associatedGroupIds;
        if (requestedGroupIds.isEmpty()) {
            associatedGroupIds = List.of(sourceGroups.adminGroupId(access.tenantId()));
        } else {
            groupScopes.validateGroupIds(access.tenantId(), requestedGroupIds);
            associatedGroupIds = requestedGroupIds;
        }
        var pair = sources.createFileSource(access.tenantId(), normalizedName);
        sourceGroups.replace(access.tenantId(), pair.sourceId(), associatedGroupIds);
        boolean globalDelete = authorization.effectiveCapabilities(requiredActorId)
                .contains(IamCapability.SOURCES_DELETE);
        return queries.detail(
                access.tenantId(),
                requiredActorId,
                pair.sourceId(),
                true,
                true,
                globalDelete
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceSummary> listSources(ActorId actorId) {
        ActorId requiredActorId = requireActorId(actorId);
        SourcePermissions permissions = readPermissions(requiredActorId);
        return queries.list(
                permissions.tenantId(),
                requiredActorId,
                permissions.globalRead(),
                permissions.globalManage(),
                permissions.globalDelete()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public SourceDetail getSource(ActorId actorId, SourceId sourceId) {
        ActorId requiredActorId = requireActorId(actorId);
        SourcePermissions permissions = readPermissions(requiredActorId);
        return queries.detail(
                permissions.tenantId(),
                requiredActorId,
                requireSourceId(sourceId),
                permissions.globalRead(),
                permissions.globalManage(),
                permissions.globalDelete()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupIdentity> listSourceGroups(ActorId actorId, SourceId sourceId) {
        ActorId requiredActorId = requireActorId(actorId);
        SourcePermissions permissions = readPermissions(requiredActorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        queries.summary(
                permissions.tenantId(),
                requiredActorId,
                requiredSourceId,
                permissions.globalRead(),
                permissions.globalManage(),
                permissions.globalDelete()
        );
        return sourceGroups.list(permissions.tenantId(), requiredSourceId);
    }

    @Override
    @Transactional
    public void replaceSourceGroups(
            ActorId actorId,
            SourceId sourceId,
            Collection<GroupId> groupIds
    ) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        List<GroupId> requiredGroupIds = normalizeGroupIds(groupIds, false);
        IamAccess access = authorization.lockAndRequireExclusive(
                requiredActorId,
                IamCapability.SOURCES_MANAGE
        );
        requireMutable(sources.lockAuthorized(
                access.tenantId(),
                requiredActorId,
                requiredSourceId,
                true
        ));
        groupScopes.validateGroupIds(access.tenantId(), requiredGroupIds);
        sourceGroups.replace(access.tenantId(), requiredSourceId, requiredGroupIds);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupIdentityPage listSourceGroupOptions(
            ActorId actorId,
            @Nullable String search,
            int page,
            int size
    ) {
        ActorId requiredActorId = requireActorId(actorId);
        IamAccess access = authorization.require(
                requiredActorId,
                IamCapability.SOURCES_MANAGE,
                false
        );
        return groupScopes.listGroupOptions(access.tenantId(), search, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceSummary> listGroupSources(ActorId actorId, GroupId groupId) {
        ActorId requiredActorId = requireActorId(actorId);
        GroupId requiredGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        SourcePermissions permissions = readPermissions(requiredActorId);
        groupScopes.validateGroupIds(permissions.tenantId(), List.of(requiredGroupId));
        if (!permissions.globalRead()
                && !groupScopes.isManagedBy(permissions.tenantId(), requiredActorId, requiredGroupId)) {
            throw SourceException.notFound();
        }
        return queries.listForGroup(
                permissions.tenantId(),
                requiredActorId,
                requiredGroupId,
                permissions.globalRead(),
                permissions.globalManage(),
                permissions.globalDelete()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceOperationView> listIndexAttempts(ActorId actorId, SourceId sourceId, int limit) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        SourcePermissions permissions = readPermissions(requiredActorId);
        queries.summary(
                permissions.tenantId(),
                requiredActorId,
                requiredSourceId,
                permissions.globalRead(),
                permissions.globalManage(),
                permissions.globalDelete()
        );
        return attempts.list(permissions.tenantId(), requiredSourceId, limit);
    }

    @Override
    public ObjectUploadAuthorization initiateUpload(
            ActorId actorId,
            SourceId sourceId,
            ObjectUploadSpecification specification
    ) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        IamAccess initialAccess = requireManagedSource(requiredActorId, requiredSourceId);
        Objects.requireNonNull(specification, "specification must not be null");
        ObjectUploadSpecification normalized = new ObjectUploadSpecification(
                requireFilename(specification.filename()),
                specification.mediaType(),
                specification.sizeBytes(),
                specification.checksum()
        );
        ObjectUploadAuthorization upload = objectUploads.initiate(initialAccess.tenantId(), normalized);
        transactions.executeWithoutResult(_ -> {
            IamAccess commitAccess = authorization.lockAndRequire(
                    requiredActorId,
                    IamCapability.SOURCES_MANAGE,
                    true
            );
            requireSameTenant(initialAccess.tenantId(), commitAccess);
            requireMutable(sources.lockAuthorized(
                    commitAccess.tenantId(),
                    requiredActorId,
                    requiredSourceId,
                    commitAccess.authority() == Authority.GLOBAL
            ));
            sourceUploads.create(commitAccess.tenantId(), requiredSourceId, upload.uploadId());
        });
        return upload;
    }

    @Override
    public SourceUploadReceipt finalizeUpload(
            ActorId actorId,
            SourceId sourceId,
            ObjectUploadId uploadId
    ) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        ObjectUploadId requiredUploadId = Objects.requireNonNull(uploadId, "uploadId must not be null");
        IamAccess initialAccess = requireManagedSource(requiredActorId, requiredSourceId);
        SourceUploadReceipt existing = receipt(initialAccess.tenantId(), requiredSourceId, requiredUploadId);
        if (existing != null) {
            return existing;
        }
        if (!sourceUploads.exists(initialAccess.tenantId(), requiredSourceId, requiredUploadId)) {
            throw SourceException.notFound();
        }
        var verified = objectUploads.verify(initialAccess.tenantId(), requiredUploadId);
        return Objects.requireNonNull(transactions.execute(_ -> {
            IamAccess commitAccess = authorization.lockAndRequire(
                    requiredActorId,
                    IamCapability.SOURCES_MANAGE,
                    true
            );
            requireSameTenant(initialAccess.tenantId(), commitAccess);
            var pair = sources.lockAuthorized(
                    commitAccess.tenantId(),
                    requiredActorId,
                    requiredSourceId,
                    commitAccess.authority() == Authority.GLOBAL
            );
            SourceUploadReceipt concurrent = receipt(
                    commitAccess.tenantId(),
                    requiredSourceId,
                    requiredUploadId
            );
            if (concurrent != null) {
                return concurrent;
            }
            var mutablePair = requireMutable(pair);
            var version = items.resolveOrCreate(
                    commitAccess.tenantId(),
                    mutablePair,
                    verified.object().filename(),
                    verified.object()
            );
            if (version.created()) {
                objectUploads.adopt(commitAccess.tenantId(), requiredUploadId, verified.token());
            } else {
                objectUploads.discard(commitAccess.tenantId(), requiredUploadId, verified.token());
            }
            SourceOperationView operation = attempts.findLive(
                            commitAccess.tenantId(),
                            requiredSourceId,
                            version
                    )
                    .orElseGet(() -> attempts.create(commitAccess.tenantId(), pair, version));
            if (!sourceUploads.complete(
                    commitAccess.tenantId(),
                    requiredSourceId,
                    requiredUploadId,
                    version,
                    operation.id()
            )) {
                throw SourceException.conflict("source upload receipt was concurrently finalized");
            }
            return new SourceUploadReceipt(
                    queries.item(commitAccess.tenantId(), requiredSourceId, version.itemId()),
                    operation
            );
        }));
    }

    @Override
    @Transactional
    public SourceOperationView reindex(ActorId actorId, SourceId sourceId, SourceItemId itemId) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        IamAccess access = authorization.lockAndRequire(
                requiredActorId,
                IamCapability.SOURCES_MANAGE,
                true
        );
        var pair = requireMutable(sources.lockAuthorized(
                access.tenantId(),
                requiredActorId,
                requiredSourceId,
                access.authority() == Authority.GLOBAL
        ));
        var version = items.lockCurrentVersion(
                access.tenantId(),
                pair,
                Objects.requireNonNull(itemId, "itemId must not be null")
        );
        return attempts.findLive(access.tenantId(), requiredSourceId, version)
                .orElseGet(() -> attempts.create(access.tenantId(), pair, version));
    }

    @Override
    @Transactional
    public SourceOperationView removeItem(ActorId actorId, SourceId sourceId, SourceItemId itemId) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        SourceItemId requiredItemId = Objects.requireNonNull(itemId, "itemId must not be null");
        IamAccess access = authorization.lockAndRequire(
                requiredActorId,
                IamCapability.SOURCES_DELETE,
                false
        );
        var pair = sources.lockAuthorized(
                access.tenantId(),
                requiredActorId,
                requiredSourceId,
                true
        );
        var parentCleanup = sources.findCleanup(
                access.tenantId(),
                SourceOperationType.DELETE_SOURCE,
                "PAIR:" + requiredSourceId.value()
        );
        if (parentCleanup.isPresent()) {
            return parentCleanup.get();
        }
        String targetKey = "ITEM:" + requiredItemId.value();
        var existing = sources.findCleanup(
                access.tenantId(),
                SourceOperationType.REMOVE_ITEM,
                targetKey
        );
        if (existing.isPresent()) {
            return existing.get();
        }
        var mutablePair = requireMutable(pair);
        items.lockCurrentVersion(access.tenantId(), mutablePair, requiredItemId);
        items.markDeleting(access.tenantId(), mutablePair, requiredItemId);
        sourceDocuments.invalidateItem(access.tenantId(), requiredSourceId, requiredItemId);
        attempts.cancelForItem(access.tenantId(), requiredSourceId, requiredItemId);
        return sources.createCleanup(
                new SourceOperationId(UUID.randomUUID()),
                access.tenantId(),
                SourceOperationType.REMOVE_ITEM,
                targetKey,
                requiredSourceId,
                requiredItemId
        );
    }

    @Override
    @Transactional
    public SourceOperationView deleteSource(ActorId actorId, SourceId sourceId) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        IamAccess access = authorization.lockAndRequire(
                requiredActorId,
                IamCapability.SOURCES_DELETE,
                false
        );
        String targetKey = "PAIR:" + requiredSourceId.value();
        var existing = sources.findCleanup(
                access.tenantId(),
                SourceOperationType.DELETE_SOURCE,
                targetKey
        );
        if (existing.isPresent()) {
            return existing.get();
        }
        var pair = sources.lockAuthorized(
                access.tenantId(),
                requiredActorId,
                requiredSourceId,
                true
        );
        existing = sources.findCleanup(
                access.tenantId(),
                SourceOperationType.DELETE_SOURCE,
                targetKey
        );
        if (existing.isPresent()) {
            return existing.get();
        }
        sources.markDeleting(access.tenantId(), pair);
        sourceDocuments.invalidateSource(access.tenantId(), requiredSourceId);
        attempts.cancelForSource(access.tenantId(), requiredSourceId);
        sources.supersedeItemCleanups(access.tenantId(), requiredSourceId);
        return sources.createCleanup(
                new SourceOperationId(UUID.randomUUID()),
                access.tenantId(),
                SourceOperationType.DELETE_SOURCE,
                targetKey,
                requiredSourceId,
                null
        );
    }

    @Override
    @Transactional(readOnly = true)
    public SourceOperationView getOperation(ActorId actorId, SourceOperationId operationId) {
        ActorId requiredActorId = requireActorId(actorId);
        SourcePermissions permissions = readPermissions(requiredActorId);
        SourceOperationId requiredOperationId =
                Objects.requireNonNull(operationId, "operationId must not be null");
        return operationQueries.findAuthorized(
                        permissions.tenantId(),
                        requiredActorId,
                        requiredOperationId,
                        permissions.globalRead()
                )
                .orElseThrow(SourceException::notFound);
    }

    private @Nullable SourceUploadReceipt receipt(
            TenantId tenantId,
            SourceId sourceId,
            ObjectUploadId uploadId
    ) {
        return sourceUploads.findReceipt(tenantId, sourceId, uploadId)
                .map(ids -> new SourceUploadReceipt(
                        queries.item(tenantId, sourceId, ids.itemId()),
                        attempts.findById(tenantId, ids.operationId()).orElseThrow()
                ))
                .orElse(null);
    }

    private IamAccess requireManagedSource(ActorId actorId, SourceId sourceId) {
        IamAccess access = authorization.require(actorId, IamCapability.SOURCES_MANAGE, true);
        boolean global = access.authority() == Authority.GLOBAL;
        queries.summary(access.tenantId(), actorId, sourceId, global, global, false);
        return access;
    }

    private SourcePermissions readPermissions(ActorId actorId) {
        ActorId requiredActorId = requireActorId(actorId);
        IamAccess access = authorization.require(requiredActorId, IamCapability.SOURCES_READ, true);
        Set<IamCapability> globalCapabilities = authorization.effectiveCapabilities(requiredActorId);
        return new SourcePermissions(
                access.tenantId(),
                globalCapabilities.contains(IamCapability.SOURCES_READ),
                globalCapabilities.contains(IamCapability.SOURCES_MANAGE),
                globalCapabilities.contains(IamCapability.SOURCES_DELETE)
        );
    }

    private static void requireSameTenant(TenantId initialTenantId, IamAccess commitAccess) {
        if (!initialTenantId.equals(commitAccess.tenantId())) {
            throw SourceException.notFound();
        }
    }

    private static ActorId requireActorId(ActorId actorId) {
        return Objects.requireNonNull(actorId, "actorId must not be null");
    }

    private static List<GroupId> normalizeGroupIds(
            Collection<GroupId> groupIds,
            boolean allowEmpty
    ) {
        Objects.requireNonNull(groupIds, "groupIds must not be null");
        LinkedHashSet<GroupId> distinct = new LinkedHashSet<>();
        for (GroupId groupId : groupIds) {
            distinct.add(Objects.requireNonNull(groupId, "groupId must not be null"));
        }
        if (!allowEmpty && distinct.isEmpty()) {
            throw SourceException.invalid(
                    "Select at least one group.",
                    "source group replacement did not contain a group"
            );
        }
        if (distinct.size() > 100) {
            throw SourceException.invalid(
                    "Select no more than 100 groups.",
                    "source group selection exceeded 100 distinct groups"
            );
        }
        return List.copyOf(distinct);
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

    private record SourcePermissions(
            TenantId tenantId,
            boolean globalRead,
            boolean globalManage,
            boolean globalDelete
    ) {
    }

}
