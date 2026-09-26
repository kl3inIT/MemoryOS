package io.memoryos.connector.source;

import io.memoryos.BusinessException;
import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditOutcome;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.Authority;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupScopeService;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceAccessPolicy {
    private final IamAuthorization authorization;
    private final JdbcSourceRepository sources;
    private final GroupScopeService groupScopes;
    private final AuditTrail audit;

    public SourceAccessPolicy(IamAuthorization authorization, JdbcSourceRepository sources,
            GroupScopeService groupScopes, AuditTrail audit) {
        this.authorization = Objects.requireNonNull(authorization);
        this.sources = Objects.requireNonNull(sources);
        this.groupScopes = Objects.requireNonNull(groupScopes);
        this.audit = Objects.requireNonNull(audit);
    }

    public IamAccess read(ActorId actorId, SourceId sourceId) {
        IamAccess access = authorization.require(actorId, IamCapability.SOURCES_READ, true);
        sources.requireAuthorized(access.tenantId(), actorId, sourceId, access.authority() == Authority.GLOBAL, false);
        return access;
    }

    public IamAccess manage(ActorId actorId, SourceId sourceId) {
        IamAccess access = authorization.require(actorId, IamCapability.SOURCES_MANAGE, true);
        try {
            sources.requireAuthorized(access.tenantId(), actorId, sourceId, access.authority() == Authority.GLOBAL, true);
        } catch (SourceException refused) {
            recordRefusal(access, actorId, sourceId);
            throw refused;
        }
        return access;
    }

    /** Whether the actor may manage the Source: a probe for which actions to offer, so its refusal is not recorded. */
    public boolean canManage(ActorId actorId, SourceId sourceId) {
        try {
            IamAccess access = authorization.require(actorId, IamCapability.SOURCES_MANAGE, true);
            sources.requireAuthorized(access.tenantId(), actorId, sourceId, access.authority() == Authority.GLOBAL, true);
            return true;
        } catch (BusinessException denied) {
            return false;
        }
    }

    /** A Source manager reaching a Source that exists but is not theirs: the refusal Onyx records as denied. */
    private void recordRefusal(IamAccess access, ActorId actorId, SourceId sourceId) {
        if (access.authority() == Authority.SCOPED && sources.exists(access.tenantId(), sourceId)) {
            audit.recordSeparately(AuditRecord.of(AuditAction.PERMISSION_DENIED, access.tenantId())
                    .outcome(AuditOutcome.DENIED).actor(actorId)
                    .resource("SOURCE", sourceId.value(), sources.auditView(access.tenantId(), sourceId)
                            .map(JdbcSourceRepository.AuditView::name).orElse(null))
                    .detail("capability", IamCapability.SOURCES_MANAGE.name()).detail("scope", "SOURCE").build());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IamAccess lockManage(ActorId actorId, SourceId sourceId) {
        IamAccess access = authorization.lockAndRequire(actorId, IamCapability.SOURCES_MANAGE, true);
        try {
            sources.lockAuthorized(access.tenantId(), actorId, sourceId, access.authority() == Authority.GLOBAL);
        } catch (SourceException refused) {
            recordRefusal(access, actorId, sourceId);
            throw refused;
        }
        IamAccess current = manage(actorId, sourceId);
        if (!access.tenantId().equals(current.tenantId())) throw SourceException.notFound();
        return current;
    }

    public Creation creation(ActorId actorId, SourceType type, @Nullable SourceAccess requestedAccess,
            Collection<GroupId> groupIds) {
        return resolve(authorization.require(actorId, IamCapability.SOURCES_MANAGE, true), actorId, type,
                requestedAccess, groupIds);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Creation lockCreation(ActorId actorId, SourceType type, @Nullable SourceAccess requestedAccess,
            Collection<GroupId> groupIds) {
        return resolve(authorization.lockAndRequireScopedMutation(actorId, IamCapability.SOURCES_MANAGE), actorId, type,
                requestedAccess, groupIds);
    }

    /**
     * FILE defaults to PUBLIC for global managers and PRIVATE for scoped managers; Google Drive defaults to SYNC.
     * SYNC needs provider permissions, and only global managers may publish to every member.
     */
    static SourceAccess access(SourceType type, boolean global, @Nullable SourceAccess requested) {
        SourceAccess access = requested != null ? requested
                : type == SourceType.GOOGLE_DRIVE ? SourceAccess.SYNC
                : global ? SourceAccess.PUBLIC : SourceAccess.PRIVATE;
        if (access == SourceAccess.SYNC && type != SourceType.GOOGLE_DRIVE) {
            throw SourceException.invalid("Auto Sync requires a Google Drive source.", "sync access without provider permissions");
        }
        if (!global && access == SourceAccess.PUBLIC) {
            throw SourceException.invalid("Managed sources cannot be public.", "scoped source publication denied");
        }
        return access;
    }

    private Creation resolve(IamAccess authority, ActorId actorId, SourceType type, @Nullable SourceAccess requestedAccess,
            Collection<GroupId> groupIds) {
        LinkedHashSet<GroupId> distinct = new LinkedHashSet<>();
        for (GroupId id : Objects.requireNonNull(groupIds, "groupIds must not be null")) {
            distinct.add(Objects.requireNonNull(id, "groupId must not be null"));
        }
        if (distinct.size() > 100) throw SourceException.invalid("Select no more than 100 groups.", "source group limit exceeded");
        boolean global = authority.authority() == Authority.GLOBAL;
        SourceAccess access = access(type, global, requestedAccess);
        List<GroupId> groups = List.copyOf(distinct);
        if (global) {
            groupScopes.validateGroupIds(authority.tenantId(), groups);
        } else {
            // A Source may start with no Group: its recorded manager attaches it later, and until then nobody
            // reads its content, because restricted reads come from Group membership alone.
            groupScopes.validateManagedGroupIds(authority.tenantId(), actorId, groups);
        }
        return new Creation(authority, access, groups);
    }

    public record Creation(IamAccess authority, SourceAccess access, List<GroupId> groupIds) {}
}
