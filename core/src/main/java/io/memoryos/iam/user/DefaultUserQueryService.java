package io.memoryos.iam.user;

import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.user.UserPage;
import io.memoryos.iam.user.UserQuery;
import io.memoryos.iam.user.UserQueryService;
import io.memoryos.iam.user.persistence.UserQueryRepository;

import java.time.Clock;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultUserQueryService implements UserQueryService {

    private final UserQueryRepository users;
    private final IamAuthorization authorization;
    private final Clock clock;

    @Autowired
    public DefaultUserQueryService(
            UserQueryRepository users,
            IamAuthorization authorization
    ) {
        this(users, authorization, Clock.systemUTC());
    }

    DefaultUserQueryService(
            UserQueryRepository users,
            IamAuthorization authorization,
            Clock clock
    ) {
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserPage list(ActorId administrator, UserQuery query) {
        Objects.requireNonNull(administrator, "administrator must not be null");
        Objects.requireNonNull(query, "query must not be null");
        TenantId tenantId = authorization.require(
                administrator,
                IamCapability.USERS_MANAGE,
                false
        ).tenantId();
        return users.findPage(tenantId, query, clock.instant());
    }
}
