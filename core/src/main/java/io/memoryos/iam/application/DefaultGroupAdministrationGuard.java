package io.memoryos.iam.application;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupAdministrationGuard;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.persistence.GroupInvariantRepository;
import io.memoryos.iam.persistence.GroupInvariantRepository.AdminState;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultGroupAdministrationGuard implements GroupAdministrationGuard {
    private final GroupInvariantRepository invariants;

    public DefaultGroupAdministrationGuard(GroupInvariantRepository invariants) {
        this.invariants = Objects.requireNonNull(invariants, "invariants must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void requireCanDeactivate(TenantId tenantId, ActorId actorId) {
        TenantId requiredTenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        ActorId requiredActorId = Objects.requireNonNull(actorId, "actorId must not be null");
        AdminState state = invariants.adminState(requiredTenantId, requiredActorId);
        if (state.configuredOwner()) {
            throw new IamException(
                    IamFailureReason.CONFIGURED_OWNER_PROTECTED,
                    "Attempted to remove or deactivate the configured Tenant owner"
            );
        }
        if (state.activeStandardAdmin() && state.activeStandardAdminCount() <= 1) {
            throw new IamException(
                    IamFailureReason.LAST_ADMIN_PROTECTED,
                    "Attempted to remove or deactivate the final active STANDARD administrator"
            );
        }
    }
}
