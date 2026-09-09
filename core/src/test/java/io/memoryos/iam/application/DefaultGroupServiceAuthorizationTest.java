package io.memoryos.iam.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.Authority;
import io.memoryos.iam.GroupAdministrationGuard;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.persistence.GroupEntity;
import io.memoryos.iam.persistence.GroupInvariantRepository;
import io.memoryos.iam.persistence.GroupMembershipRepository;
import io.memoryos.iam.persistence.GroupProjectionRepository;
import io.memoryos.iam.persistence.GroupRepository;
import io.memoryos.iam.persistence.IamLockRepository;
import io.memoryos.iam.persistence.TenantEntity;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultGroupServiceAuthorizationTest {
    private static final TenantId TENANT = new TenantId(
            UUID.fromString("10000000-0000-0000-0000-000000000055")
    );
    private static final ActorId MANAGER = new ActorId(
            UUID.fromString("20000000-0000-0000-0000-000000000055")
    );
    private static final ActorId TARGET = new ActorId(
            UUID.fromString("30000000-0000-0000-0000-000000000055")
    );
    private static final GroupId GROUP_ID = new GroupId(
            UUID.fromString("40000000-0000-0000-0000-000000000055")
    );

    @Test
    void scopedManagerCannotAddAMemberWhoWouldReceiveCapabilitiesTheManagerDoesNotHold() {
        IamAuthorization authorization = mock(IamAuthorization.class);
        IamLockRepository locks = mock(IamLockRepository.class);
        GroupRepository groups = mock(GroupRepository.class);
        GroupMembershipRepository memberships = mock(GroupMembershipRepository.class);
        GroupCapabilityGrantRepository grants = mock(GroupCapabilityGrantRepository.class);
        GroupProjectionRepository projections = mock(GroupProjectionRepository.class);
        GroupInvariantRepository invariants = mock(GroupInvariantRepository.class);
        GroupAdministrationGuard administrationGuard = mock(GroupAdministrationGuard.class);
        var service = new DefaultGroupService(
                authorization,
                locks,
                groups,
                memberships,
                grants,
                projections,
                invariants,
                administrationGuard
        );
        GroupEntity group = new GroupEntity(
                new TenantEntity(TENANT.value(), "tenant", "Tenant", "test"),
                GROUP_ID.value(),
                "Restricted",
                null
        );
        when(authorization.require(MANAGER, IamCapability.GROUPS_MANAGE, true))
                .thenReturn(new IamAccess(TENANT, Authority.SCOPED));
        when(groups.find(TENANT, GROUP_ID)).thenReturn(Optional.of(group));
        when(invariants.isManagedBy(TENANT, MANAGER, GROUP_ID)).thenReturn(true);
        when(grants.findCapabilities(group)).thenReturn(Set.of(IamCapability.SOURCES_MANAGE));
        when(authorization.effectiveCapabilities(MANAGER)).thenReturn(Set.of(IamCapability.SOURCES_READ));

        IamException failure = assertThrows(
                IamException.class,
                () -> service.addMembers(MANAGER, GROUP_ID, Set.of(TARGET))
        );

        assertEquals("IAM_MANAGER_AMPLIFICATION_DENIED", failure.code());
    }
}
