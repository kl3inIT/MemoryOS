package io.memoryos.iam.user;

import io.memoryos.iam.GroupScopeService;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.PrincipalGroup;
import io.memoryos.iam.PrincipalMatches;
import io.memoryos.iam.PrincipalQuery;
import io.memoryos.iam.PrincipalSearch;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantMembership;
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
    private final TenantAccessResolver tenants;

    public DefaultPrincipalSearch(UserQueryRepository users, GroupScopeService groups, TenantAccessResolver tenants) {
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.groups = Objects.requireNonNull(groups, "groups must not be null");
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PrincipalMatches search(ActorId searcher, PrincipalQuery query) {
        Objects.requireNonNull(searcher, "searcher must not be null");
        Objects.requireNonNull(query, "query must not be null");
        TenantId tenantId = tenants.findActiveMembership(searcher).map(TenantMembership::tenantId)
                .orElseThrow(() -> new IamException(IamFailureReason.ACCESS_DENIED,
                        "Active membership required to search people and Groups"));
        return new PrincipalMatches(
                users.searchActiveMembers(tenantId, query.search(), query.size()),
                groups.listGroupOptions(tenantId, query.search(), 0, query.size()).items().stream()
                        .map(group -> new PrincipalGroup(group.id(), group.name()))
                        .toList());
    }
}
