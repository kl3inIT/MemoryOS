package io.memoryos.connector.application;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.group.Authority;
import io.memoryos.iam.group.GroupId;
import io.memoryos.iam.group.GroupScopeService;
import io.memoryos.iam.group.IamAccess;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
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

    public SourceAccessPolicy(IamAuthorization authorization, JdbcSourceRepository sources,
            GroupScopeService groupScopes) {
        this.authorization = Objects.requireNonNull(authorization);
        this.sources = Objects.requireNonNull(sources);
        this.groupScopes = Objects.requireNonNull(groupScopes);
    }

    public IamAccess read(ActorId actorId, SourceId sourceId) {
        IamAccess access = authorization.require(actorId, IamCapability.SOURCES_READ, true);
        sources.requireAuthorized(access.tenantId(), actorId, sourceId, access.authority() == Authority.GLOBAL, false);
        return access;
    }

    public IamAccess manage(ActorId actorId, SourceId sourceId) {
        IamAccess access = authorization.require(actorId, IamCapability.SOURCES_MANAGE, true);
        sources.requireAuthorized(access.tenantId(), actorId, sourceId, access.authority() == Authority.GLOBAL, true);
        return access;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IamAccess lockManage(ActorId actorId, SourceId sourceId) {
        IamAccess access = authorization.lockAndRequire(actorId, IamCapability.SOURCES_MANAGE, true);
        sources.lockAuthorized(access.tenantId(), actorId, sourceId, access.authority() == Authority.GLOBAL);
        IamAccess current = manage(actorId, sourceId);
        if (!access.tenantId().equals(current.tenantId())) throw SourceException.notFound();
        return current;
    }

    public Creation creation(ActorId actorId, @Nullable SourceAccess requestedAccess, Collection<GroupId> groupIds) {
        return resolve(authorization.require(actorId, IamCapability.SOURCES_MANAGE, true), actorId, requestedAccess, groupIds);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Creation lockCreation(ActorId actorId, @Nullable SourceAccess requestedAccess, Collection<GroupId> groupIds) {
        return resolve(authorization.lockAndRequireScopedMutation(actorId, IamCapability.SOURCES_MANAGE), actorId,
                requestedAccess, groupIds);
    }

    private Creation resolve(IamAccess authority, ActorId actorId, @Nullable SourceAccess requestedAccess,
            Collection<GroupId> groupIds) {
        LinkedHashSet<GroupId> distinct = new LinkedHashSet<>();
        for (GroupId id : Objects.requireNonNull(groupIds, "groupIds must not be null")) {
            distinct.add(Objects.requireNonNull(id, "groupId must not be null"));
        }
        if (distinct.size() > 100) throw SourceException.invalid("Select no more than 100 groups.", "source group limit exceeded");
        boolean global = authority.authority() == Authority.GLOBAL;
        SourceAccess access = requestedAccess == null ? (global ? SourceAccess.PUBLIC : SourceAccess.PRIVATE) : requestedAccess;
        if (!global && access == SourceAccess.PUBLIC) {
            throw SourceException.invalid("Managed sources must be restricted.", "scoped source publication denied");
        }
        List<GroupId> groups = List.copyOf(distinct);
        if (global) {
            groupScopes.validateGroupIds(authority.tenantId(), groups);
        } else {
            if (groups.isEmpty()) throw SourceException.invalid("Select at least one group.", "scoped source creation requires groups");
            groupScopes.validateManagedGroupIds(authority.tenantId(), actorId, groups);
        }
        return new Creation(authority, access, groups);
    }

    public record Creation(IamAccess authority, SourceAccess access, List<GroupId> groupIds) {}
}
