package io.memoryos.iam.user;

import io.memoryos.iam.GroupScopeService;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.PrincipalGroup;
import io.memoryos.iam.PrincipalMatches;
import io.memoryos.iam.PrincipalQuery;
import io.memoryos.iam.PrincipalSearch;
import io.memoryos.iam.user.persistence.UserQueryRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultPrincipalSearch implements PrincipalSearch {

    private final UserQueryRepository users;
    private final GroupScopeService groups;
    private final IamAuthorization authorization;

    public DefaultPrincipalSearch(UserQueryRepository users, GroupScopeService groups, IamAuthorization authorization) {
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.groups = Objects.requireNonNull(groups, "groups must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PrincipalMatches search(ActorId searcher, PrincipalQuery query) {
        Objects.requireNonNull(searcher, "searcher must not be null");
        Objects.requireNonNull(query, "query must not be null");
        TenantId tenantId = authorization.require(searcher, IamCapability.CHAT_WRITE, false).tenantId();
        return new PrincipalMatches(
                users.searchActiveMembers(tenantId, query.search(), query.size()),
                groups.listGroupOptions(tenantId, query.search(), 0, query.size()).items().stream()
                        .map(group -> new PrincipalGroup(group.id(), group.name()))
                        .toList());
    }
}
