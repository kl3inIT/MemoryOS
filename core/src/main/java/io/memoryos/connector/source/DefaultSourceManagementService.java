package io.memoryos.connector.source;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.connector.GroupSources;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceItemPage;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceStatus;
import io.memoryos.connector.SourceSummary;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.SourceUploadReceipt;
import io.memoryos.connector.googledrive.GoogleDriveConnectionService;
import io.memoryos.connector.sync.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.source.persistence.JdbcSourceGroupRepository;
import io.memoryos.connector.source.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.source.persistence.JdbcSourceOperationQueryRepository;
import io.memoryos.connector.source.persistence.JdbcSourceQueryRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.connector.source.persistence.JdbcSourceUploadRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.Authority;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupIdentity;
import io.memoryos.iam.GroupIdentityPage;
import io.memoryos.iam.GroupScopeService;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.TenantId;
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
    private final SourceAccessPolicy sourceAccess;
    private final TransactionTemplate transactions;
    private final io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository sync;
    private final io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSelectionRepository selections;
    private final AuditTrail audit;
    private final io.memoryos.connector.googledrive.GoogleDriveConnectionService connections;

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
            PlatformTransactionManager transactionManager,
            io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository sync,
            io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSelectionRepository selections,
            io.memoryos.connector.googledrive.GoogleDriveConnectionService connections,
            SourceAccessPolicy sourceAccess
    ,
            AuditTrail audit) {
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
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
        this.sync = Objects.requireNonNull(sync);
        this.selections = Objects.requireNonNull(selections);
        this.connections = Objects.requireNonNull(connections);
        this.sourceAccess = Objects.requireNonNull(sourceAccess);
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    @Override
    @Transactional
    public SourceSummary createFileSource(
            ActorId actorId,
            String name,
            Collection<GroupId> groupIds,
            @Nullable SourceAccess requestedAccess
    ) {
        ActorId requiredActorId = requireActorId(actorId);
        String normalizedName = requireName(name);
        var creation = sourceAccess.lockCreation(requiredActorId, SourceType.FILE, requestedAccess, groupIds);
        IamAccess access = creation.authority();
        var pair = sources.createFileSource(access.tenantId(), requiredActorId, normalizedName, creation.access(),
                sourceManagerFor(access, requiredActorId));
        sourceGroups.replace(access.tenantId(), pair.sourceId(), creation.groupIds());
        record(access.tenantId(), requiredActorId, AuditAction.SOURCE_CREATE, pair.sourceId(), event -> event
                .detail("provider", SourceType.FILE.name()).detail("access", creation.access().name())
                .detail("groups", groupNames(access.tenantId(), pair.sourceId())));
        return getSource(requiredActorId, pair.sourceId());
    }

    @Override
    @Transactional
    public SourceSummary renameSource(ActorId actorId, SourceId sourceId, String name) {
        String normalizedName = requireName(name);
        IamAccess access = sourceAccess.lockManage(actorId, sourceId);
        var pair = requireMutable(sources.lock(access.tenantId(), sourceId));
        String before = sources.auditView(access.tenantId(), sourceId).map(JdbcSourceRepository.AuditView::name).orElse(null);
        sources.rename(access.tenantId(), pair, normalizedName);
        if (!normalizedName.equals(before)) record(access.tenantId(), actorId, AuditAction.SOURCE_UPDATE, sourceId,
                event -> event.detail("change", "RENAME").detail("before", before).detail("after", normalizedName));
        return getSource(actorId, sourceId);
    }

    @Override
    @Transactional
    public SourceSummary updateSourceAccess(ActorId actorId, SourceId sourceId, SourceAccess requestedAccess) {
        Objects.requireNonNull(requestedAccess, "access must not be null");
        IamAccess access = authorization.lockAndRequireExclusive(actorId, IamCapability.SOURCES_MANAGE);
        requireMutable(sources.lock(access.tenantId(), sourceId));
        String before = sources.auditView(access.tenantId(), sourceId).map(JdbcSourceRepository.AuditView::access).orElse(null);
        sources.updateAccess(access.tenantId(), sourceId, requestedAccess);
        if (!requestedAccess.name().equals(before)) record(access.tenantId(), actorId, AuditAction.SOURCE_ACCESS_CHANGE,
                sourceId, event -> event.detail("before", before).detail("after", requestedAccess.name()));
        return getSource(actorId, sourceId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceSummary> listSources(ActorId actorId) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceReadAuthority permissions = readPermissions(requiredActorId);
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
    public SourceSummary getSource(ActorId actorId, SourceId sourceId) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceReadAuthority permissions = readPermissions(requiredActorId);
        return queries.summary(
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
        SourceReadAuthority permissions = readPermissions(requiredActorId);
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
        IamAccess access = authorization.lockAndRequireScopedMutation(
                requiredActorId,
                IamCapability.SOURCES_MANAGE
        );
        boolean global = access.authority() == Authority.GLOBAL;
        requireMutable(sources.lockAuthorized(
                access.tenantId(),
                requiredActorId,
                requiredSourceId,
                global
        ));
        List<GroupId> requiredGroupIds = normalizeGroupIds(groupIds);
        if (global) {
            groupScopes.validateGroupIds(access.tenantId(), requiredGroupIds);
        } else {
            // The recorded manager attaches and detaches only their own Groups; an association with a Group they
            // do not manage belongs to that Group's manager and has to survive the replacement unchanged.
            Set<GroupId> current = sourceGroups.groupIds(access.tenantId(), requiredSourceId);
            Set<GroupId> changed = new LinkedHashSet<>(requiredGroupIds);
            changed.removeAll(current);
            current.stream().filter(groupId -> !requiredGroupIds.contains(groupId)).forEach(changed::add);
            groupScopes.validateManagedGroupIds(access.tenantId(), requiredActorId, changed);
        }
        List<String> before = groupNames(access.tenantId(), requiredSourceId);
        sourceGroups.replace(access.tenantId(), requiredSourceId, requiredGroupIds);
        List<String> after = groupNames(access.tenantId(), requiredSourceId);
        if (!before.equals(after)) record(access.tenantId(), requiredActorId, AuditAction.SOURCE_GROUP_CHANGE,
                requiredSourceId, event -> event
                        .detail("added", after.stream().filter(name -> !before.contains(name)).toList())
                        .detail("removed", before.stream().filter(name -> !after.contains(name)).toList()));
    }

    @Override
    @Transactional
    public SourceSummary assignSourceManager(
            ActorId actorId,
            SourceId sourceId,
            @Nullable ActorId managerActorId
    ) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        IamAccess access = authorization.lockAndRequireAdministration(requiredActorId);
        requireMutable(sources.lock(access.tenantId(), requiredSourceId));
        if (managerActorId != null
                && !groupScopes.managesAnyOrdinaryGroup(access.tenantId(), managerActorId)) {
            throw SourceException.managerNotEligible();
        }
        UUID before = sources.auditView(access.tenantId(), requiredSourceId).map(JdbcSourceRepository.AuditView::manager)
                .orElse(null);
        sources.assignManager(access.tenantId(), requiredSourceId, managerActorId);
        UUID after = managerActorId == null ? null : managerActorId.value();
        if (!Objects.equals(before, after)) record(access.tenantId(), requiredActorId, AuditAction.SOURCE_MANAGER_CHANGE,
                requiredSourceId, event -> event.detail("before", person(before)).detail("after", person(after)));
        return getSource(requiredActorId, requiredSourceId);
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
                true
        );
        return access.authority() == Authority.GLOBAL
                ? groupScopes.listGroupOptions(access.tenantId(), search, page, size)
                : groupScopes.listManagedGroupOptions(access.tenantId(), requiredActorId, search, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public GroupSources listGroupSources(ActorId actorId, GroupId groupId) {
        ActorId requiredActorId = requireActorId(actorId);
        GroupId requiredGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        SourceReadAuthority permissions = readPermissions(requiredActorId);
        groupScopes.validateGroupIds(permissions.tenantId(), List.of(requiredGroupId));
        boolean managedGroup = groupScopes.isManagedBy(permissions.tenantId(), requiredActorId, requiredGroupId);
        if (!permissions.globalRead() && !managedGroup) {
            throw SourceException.notFound();
        }
        List<SourceSummary> associated = queries.listForGroup(
                permissions.tenantId(),
                requiredActorId,
                requiredGroupId,
                permissions.globalRead(),
                permissions.globalManage(),
                permissions.globalDelete()
        );
        boolean manages = permissions.globalManage()
                || (managedGroup && authorization.scopedCapabilities(requiredActorId)
                        .contains(IamCapability.SOURCES_MANAGE));
        if (!manages) {
            return new GroupSources(associated, Set.of());
        }
        Set<SourceId> removable = new LinkedHashSet<>(sourceGroups.removableFromGroup(
                permissions.tenantId(),
                requiredActorId,
                requiredGroupId,
                permissions.globalManage()
        ));
        removable.retainAll(associated.stream().map(SourceSummary::id).toList());
        return new GroupSources(associated, removable);
    }

    @Override
    @Transactional
    public void removeGroupSource(ActorId actorId, GroupId groupId, SourceId sourceId) {
        ActorId requiredActorId = requireActorId(actorId);
        GroupId requiredGroupId = Objects.requireNonNull(groupId, "groupId must not be null");
        SourceId requiredSourceId = requireSourceId(sourceId);
        IamAccess access = authorization.lockAndRequireScopedMutation(
                requiredActorId,
                IamCapability.SOURCES_MANAGE
        );
        // Detaching needs the same authority as attaching: the Source's recorded manager, within a Group they manage.
        // Another manager of that Group cannot touch the Source. The Source's other Groups are never changed.
        boolean global = access.authority() == Authority.GLOBAL;
        if (global) {
            groupScopes.validateGroupIds(access.tenantId(), List.of(requiredGroupId));
        } else {
            groupScopes.validateManagedGroupIds(access.tenantId(), requiredActorId, List.of(requiredGroupId));
        }
        requireMutable(sources.lockAuthorized(access.tenantId(), requiredActorId, requiredSourceId, global));
        if (!sourceGroups.groupIds(access.tenantId(), requiredSourceId).contains(requiredGroupId)) {
            throw SourceException.notFound();
        }
        String groupName = sourceGroups.list(access.tenantId(), requiredSourceId).stream()
                .filter(group -> group.id().equals(requiredGroupId)).map(GroupIdentity::name).findFirst().orElse(null);
        sourceGroups.remove(access.tenantId(), requiredSourceId, requiredGroupId);
        record(access.tenantId(), requiredActorId, AuditAction.SOURCE_GROUP_CHANGE, requiredSourceId,
                event -> event.detail("added", List.of()).detail("removed", groupName == null ? List.of() : List.of(groupName)));
    }

    @Override
    @Transactional(readOnly = true)
    public SourceItemPage listItems(ActorId actorId, SourceId sourceId, @Nullable String cursor, int size) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceReadAuthority permissions = readPermissions(requiredActorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        queries.summary(permissions.tenantId(), requiredActorId, requiredSourceId,
                permissions.globalRead(), permissions.globalManage(), permissions.globalDelete());
        if (size < 1 || size > 100) {
            throw SourceException.invalid("Page size must be between 1 and 100.", "invalid source item page size");
        }
        return queries.items(permissions.tenantId(), requiredSourceId, cursor, size);
    }

    @Override
    @Transactional(readOnly = true)
    public io.memoryos.connector.SourceOperationPage listIndexAttempts(ActorId actorId, SourceId sourceId, @Nullable String cursor, int limit) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        SourceReadAuthority permissions = readPermissions(requiredActorId);
        queries.summary(
                permissions.tenantId(),
                requiredActorId,
                requiredSourceId,
                permissions.globalRead(),
                permissions.globalManage(),
                permissions.globalDelete()
        );
        return attempts.list(permissions.tenantId(), requiredSourceId, cursor, limit);
    }

    @Override
    public ObjectUploadAuthorization initiateUpload(
            ActorId actorId,
            SourceId sourceId,
            ObjectUploadSpecification specification
    ) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        IamAccess initialAccess = requireManagedFileSource(requiredActorId, requiredSourceId);
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
        IamAccess initialAccess = requireManagedFileSource(requiredActorId, requiredSourceId);
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
        if (pair.status() == SourceStatus.PAUSED) throw SourceException.conflict("source is paused");
        var version = items.lockCurrentVersion(
                access.tenantId(),
                pair,
                Objects.requireNonNull(itemId, "itemId must not be null")
        );
        if (!attempts.canReplay(access.tenantId(), requiredSourceId, version.versionId()))
            throw SourceException.conflict("source item must complete an authorized synchronization before reindexing");
        return attempts.findLive(access.tenantId(), requiredSourceId, version)
                .orElseGet(() -> attempts.create(access.tenantId(), pair, version));
    }

    @Override
    @Transactional
    public SourceSummary pauseSource(ActorId actorId, SourceId sourceId) {
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
        if (pair.status() != SourceStatus.PAUSED) {
            sources.setPaused(access.tenantId(), requiredSourceId);
            sync.cancelQueuedForPause(access.tenantId(), requiredSourceId);
            attempts.cancelQueuedForPause(access.tenantId(), requiredSourceId);
            record(access.tenantId(), requiredActorId, AuditAction.SOURCE_PAUSE, requiredSourceId, event -> event);
        }
        return getSource(requiredActorId, requiredSourceId);
    }

    @Override
    @Transactional
    public SourceSummary resumeSource(ActorId actorId, SourceId sourceId) {
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
        if (pair.status() == SourceStatus.PAUSED) {
            sources.clearPaused(access.tenantId(), requiredSourceId);
            attempts.requeuePaused(access.tenantId(), pair);
            if (getSource(requiredActorId, requiredSourceId).type() == io.memoryos.connector.SourceType.GOOGLE_DRIVE) {
                try {
                    var state = connections.state(access.tenantId(), requiredSourceId);
                    if (connections.current(access.tenantId(), requiredSourceId, state.credentialRevision())) {
                        sync.enqueueResumed(access.tenantId(), requiredSourceId, state.credentialRevision(), requiredActorId);
                    }
                } catch (SourceException exception) {
                    if (!"SOURCE_NOT_FOUND".equals(exception.code())) throw exception;
                }
            }
            sources.recomputeStatus(access.tenantId(), requiredSourceId, false);
            record(access.tenantId(), requiredActorId, AuditAction.SOURCE_RESUME, requiredSourceId, event -> event);
        }
        return getSource(requiredActorId, requiredSourceId);
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
        sync.exclude(access.tenantId(), requiredSourceId, requiredItemId);
        items.markDeleting(access.tenantId(), mutablePair, requiredItemId);
        sourceDocuments.invalidateItem(access.tenantId(), requiredSourceId, requiredItemId);
        attempts.cancelForItem(access.tenantId(), requiredSourceId, requiredItemId);
        record(access.tenantId(), requiredActorId, AuditAction.SOURCE_ITEM_REMOVE, requiredSourceId,
                event -> event.detail("item", requiredItemId.value().toString()));
        return sources.createCleanup(
                new SourceOperationId(UUID.randomUUID()),
                access.tenantId(),
                SourceOperationType.REMOVE_ITEM,
                targetKey,
                requiredSourceId,
                requiredItemId,
                null
        );
    }

    @Override
    @Transactional
    public SourceOperationView deleteSource(ActorId actorId, SourceId sourceId) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceId requiredSourceId = requireSourceId(sourceId);
        boolean globalDelete = authorization.effectiveCapabilities(requiredActorId).contains(IamCapability.SOURCES_DELETE);
        IamAccess access = authorization.lockAndRequire(requiredActorId,
                globalDelete ? IamCapability.SOURCES_DELETE : IamCapability.SOURCES_MANAGE, !globalDelete);
        String targetKey = "PAIR:" + requiredSourceId.value();
        var existing = sources.findCleanup(
                access.tenantId(),
                SourceOperationType.DELETE_SOURCE,
                targetKey
        );
        if (existing.isPresent()) {
            if (!globalDelete && !sources.ownsCleanup(access.tenantId(), requiredActorId, existing.get().id())) {
                throw SourceException.notFound();
            }
            return existing.get();
        }
        var pair = sources.lockAuthorized(
                access.tenantId(),
                requiredActorId,
                requiredSourceId,
                globalDelete
        );
        if (!globalDelete) sources.requireCreatorGroupless(access.tenantId(), requiredActorId, requiredSourceId);
        existing = sources.findCleanup(
                access.tenantId(),
                SourceOperationType.DELETE_SOURCE,
                targetKey
        );
        if (existing.isPresent()) {
            return existing.get();
        }
        var deleted = sources.auditView(access.tenantId(), requiredSourceId);
        sources.markDeleting(access.tenantId(), pair);
        audit.record(AuditRecord.of(AuditAction.SOURCE_DELETE, access.tenantId()).actor(requiredActorId)
                .resource("SOURCE", requiredSourceId.value(), deleted.map(JdbcSourceRepository.AuditView::name).orElse(null))
                .detail("provider", deleted.map(JdbcSourceRepository.AuditView::provider).orElse(null)).build());
        sourceDocuments.invalidateSource(access.tenantId(), requiredSourceId);
        attempts.cancelForSource(access.tenantId(), requiredSourceId);
        sync.cancel(access.tenantId(), requiredSourceId);
        selections.cancelForSource(access.tenantId(), requiredSourceId);
        sources.supersedeItemCleanups(access.tenantId(), requiredSourceId);
        return sources.createCleanup(
                new SourceOperationId(UUID.randomUUID()),
                access.tenantId(),
                SourceOperationType.DELETE_SOURCE,
                targetKey,
                requiredSourceId,
                null,
                globalDelete ? null : requiredActorId
        );
    }

    @Override
    @Transactional(readOnly = true)
    public SourceOperationView getOperation(ActorId actorId, SourceOperationId operationId) {
        ActorId requiredActorId = requireActorId(actorId);
        SourceReadAuthority permissions = readPermissions(requiredActorId);
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

    private IamAccess requireManagedFileSource(ActorId actorId, SourceId sourceId) {
        IamAccess access = sourceAccess.manage(actorId, sourceId);
        boolean global = access.authority() == Authority.GLOBAL;
        var source = queries.summary(access.tenantId(), actorId, sourceId, global, global, false);
        if (source.type() != io.memoryos.connector.SourceType.FILE)
            throw SourceException.conflict("browser uploads require a FILE source");
        if (source.status() == SourceStatus.PAUSED || source.status() == SourceStatus.PAUSING)
            throw SourceException.conflict("source is paused");
        return access;
    }

    private SourceReadAuthority readPermissions(ActorId actorId) {
        ActorId requiredActorId = requireActorId(actorId);
        
        IamAccess access = authorization.require(requiredActorId, IamCapability.SOURCES_READ, true);
        Set<IamCapability> globalCapabilities = authorization.effectiveCapabilities(requiredActorId);
        return new SourceReadAuthority(
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

    /** Scoped creation records its creator; global creation leaves the Source to global authority alone. */
    private static @Nullable ActorId sourceManagerFor(IamAccess access, ActorId actorId) {
        return access.authority() == Authority.GLOBAL ? null : actorId;
    }

    private static List<GroupId> normalizeGroupIds(Collection<GroupId> groupIds) {
        Objects.requireNonNull(groupIds, "groupIds must not be null");
        LinkedHashSet<GroupId> distinct = new LinkedHashSet<>();
        for (GroupId groupId : groupIds) {
            distinct.add(Objects.requireNonNull(groupId, "groupId must not be null"));
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

    private record SourceReadAuthority(
            TenantId tenantId,
            boolean globalRead,
            boolean globalManage,
            boolean globalDelete
    ) {
    }


    private void record(TenantId tenant, ActorId actor, AuditAction action, SourceId sourceId,
                        java.util.function.UnaryOperator<AuditRecord.Builder> details) {
        String name = sources.auditView(tenant, sourceId).map(JdbcSourceRepository.AuditView::name).orElse(null);
        audit.record(details.apply(AuditRecord.of(action, tenant).actor(actor)
                .resource("SOURCE", sourceId.value(), name)).build());
    }

    private List<String> groupNames(TenantId tenant, SourceId sourceId) {
        return sourceGroups.list(tenant, sourceId).stream().map(GroupIdentity::name).sorted().toList();
    }

    private @Nullable String person(@Nullable UUID actor) {
        if (actor == null) return null;
        var person = audit.person(new ActorId(actor));
        return person.email() != null ? person.email() : person.label();
    }
}
