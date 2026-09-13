package io.memoryos.iam.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.persistence.GroupEntity;
import io.memoryos.iam.persistence.GroupInvariantRepository;
import io.memoryos.iam.persistence.GroupMembershipRepository;
import io.memoryos.iam.persistence.GroupProjectionRepository;
import io.memoryos.iam.persistence.GroupRepository;
import io.memoryos.iam.persistence.TenantEntity;

import java.util.EnumSet;
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
        GroupRepository groups = mock(GroupRepository.class);
        GroupMembershipRepository memberships = mock(GroupMembershipRepository.class);
        GroupCapabilityGrantRepository grants = mock(GroupCapabilityGrantRepository.class);
        GroupProjectionRepository projections = mock(GroupProjectionRepository.class);
        GroupInvariantRepository invariants = mock(GroupInvariantRepository.class);
        GroupAdministrationGuard administrationGuard = mock(GroupAdministrationGuard.class);
        var service = new DefaultGroupService(
                authorization,
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
        when(authorization.lockAndRequireScopedMutation(MANAGER, IamCapability.GROUPS_MANAGE))
                .thenReturn(new IamAccess(TENANT, Authority.SCOPED));
        when(groups.find(TENANT, GROUP_ID)).thenReturn(Optional.of(group));
        when(invariants.isManagedBy(TENANT, MANAGER, GROUP_ID)).thenReturn(true);
        when(grants.findCapabilities(group)).thenReturn(Set.of(IamCapability.SOURCES_MANAGE));
        when(authorization.effectiveCapabilities(MANAGER)).thenReturn(Set.of());

        IamException failure = assertThrows(
                IamException.class,
                () -> service.addMembers(MANAGER, GROUP_ID, Set.of(TARGET))
        );

        assertEquals("IAM_MANAGER_AMPLIFICATION_DENIED", failure.code());
    }

    @Test
    void publicGrantMutationRejectsSystemBundlesAndAllDerivedCapabilities() {
        var service = new DefaultGroupService(
                mock(IamAuthorization.class),
                mock(GroupRepository.class),
                mock(GroupMembershipRepository.class),
                mock(GroupCapabilityGrantRepository.class),
                mock(GroupProjectionRepository.class),
                mock(GroupInvariantRepository.class),
                mock(GroupAdministrationGuard.class)
        );

        for (IamCapability capability : Set.of(
                IamCapability.SYSTEM_ADMIN, IamCapability.SYSTEM_BASIC, IamCapability.SEARCH_READ,
                IamCapability.CHAT_READ, IamCapability.CHAT_WRITE,
                IamCapability.IMAGE_GENERATE, IamCapability.LLM_GATEWAY_USE, IamCapability.GROUPS_READ,
                IamCapability.SOURCES_READ, IamCapability.SOURCES_DELETE
        )) {
            var failure = assertThrows(IamException.class,
                    () -> service.replaceCapabilities(MANAGER, GROUP_ID, Set.of(capability)));
            assertEquals(IamFailureReason.GROUP_PROTECTED.code(), failure.code());
        }
    }

    @Test
    void capabilityRegistryDescribesEveryCapabilityOnceWithOnlyOrdinaryGrantsEditable() {
        var service = new DefaultGroupService(
                mock(IamAuthorization.class),
                mock(GroupRepository.class),
                mock(GroupMembershipRepository.class),
                mock(GroupCapabilityGrantRepository.class),
                mock(GroupProjectionRepository.class),
                mock(GroupInvariantRepository.class),
                mock(GroupAdministrationGuard.class)
        );

        var registry = service.capabilities(MANAGER);
        EnumSet<IamCapability> registered = EnumSet.noneOf(IamCapability.class);
        EnumSet<IamCapability> editable = EnumSet.noneOf(IamCapability.class);
        for (var metadata : registry) {
            assertTrue(registered.add(metadata.id()), () -> "Duplicate capability: " + metadata.id());
            if (metadata.editable()) {
                editable.add(metadata.id());
            }
            assertFalse(metadata.label().isBlank(), () -> "Missing label: " + metadata.id());
            assertFalse(metadata.description().isBlank(), () -> "Missing description: " + metadata.id());
            switch (metadata.id()) {
                case SYSTEM_ADMIN -> {
                    assertFalse(metadata.editable());
                    assertEquals(EnumSet.complementOf(EnumSet.of(IamCapability.SYSTEM_ADMIN)), metadata.implies());
                }
                case SYSTEM_BASIC -> {
                    assertFalse(metadata.editable());
                    assertEquals(Set.of(IamCapability.SEARCH_READ, IamCapability.CHAT_READ,
                            IamCapability.CHAT_WRITE, IamCapability.IMAGE_GENERATE,
                            IamCapability.LLM_GATEWAY_USE), metadata.implies());
                }
                case CHAT_WRITE -> {
                    assertFalse(metadata.editable());
                    assertEquals(Set.of(IamCapability.CHAT_READ), metadata.implies());
                }
                case SEARCH_READ, CHAT_READ, IMAGE_GENERATE, LLM_GATEWAY_USE, GROUPS_READ, SOURCES_READ -> {
                    assertFalse(metadata.editable());
                    assertEquals(Set.of(), metadata.implies());
                }
                case GROUPS_MANAGE -> {
                    assertTrue(metadata.editable());
                    assertEquals(Set.of(IamCapability.GROUPS_READ), metadata.implies());
                }
                case SOURCES_MANAGE -> {
                    assertTrue(metadata.editable());
                    assertEquals(Set.of(IamCapability.SOURCES_READ, IamCapability.SOURCES_DELETE), metadata.implies());
                }
                case SOURCES_DELETE -> {
                    assertFalse(metadata.editable());
                    assertEquals(Set.of(IamCapability.SOURCES_READ), metadata.implies());
                }
                default -> assertTrue(metadata.editable());
            }
        }
        assertEquals(EnumSet.allOf(IamCapability.class), registered);
        assertEquals(Set.of(IamCapability.USERS_MANAGE, IamCapability.GROUPS_MANAGE,
                IamCapability.SOURCES_MANAGE, IamCapability.MODELS_MANAGE), editable);
    }
}
