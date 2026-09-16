package io.memoryos.iam.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import io.memoryos.iam.group.IamCapability;

class IamCapabilityTest {

    @Test
    void permitsExactlyTheOrdinaryAdministrativeGrants() {
        EnumSet<IamCapability> ordinaryGrants = EnumSet.noneOf(IamCapability.class);
        for (IamCapability capability : IamCapability.values()) {
            if (capability.isOrdinaryGrant()) {
                ordinaryGrants.add(capability);
            }
        }
        assertEquals(
                Set.of(IamCapability.USERS_MANAGE, IamCapability.GROUPS_MANAGE,
                        IamCapability.SOURCES_MANAGE, IamCapability.MODELS_MANAGE, IamCapability.MCP_MANAGE),
                ordinaryGrants
        );
    }

    @Test
    void basicAccessExpandsToBaselineAndReservedRightsWithoutAdministrativeAuthority() {
        assertEquals(
                Set.of(IamCapability.SYSTEM_BASIC, IamCapability.SEARCH_READ,
                        IamCapability.CHAT_READ, IamCapability.CHAT_WRITE,
                        IamCapability.IMAGE_GENERATE, IamCapability.LLM_GATEWAY_USE),
                IamCapability.expand(Set.of(IamCapability.SYSTEM_BASIC))
        );
        assertEquals(Set.of(), IamCapability.expand(Set.of()));
    }

    @Test
    void basicImpliesExactlyFiveDerivedCapabilities() {
        assertEquals(
                Set.of(IamCapability.SEARCH_READ, IamCapability.CHAT_READ, IamCapability.CHAT_WRITE,
                        IamCapability.IMAGE_GENERATE, IamCapability.LLM_GATEWAY_USE),
                IamCapability.SYSTEM_BASIC.impliedCapabilities()
        );
    }

    @Test
    void writingChatsImpliesReadingButReadingDoesNotImplyWriting() {
        assertEquals(Set.of(IamCapability.CHAT_READ), IamCapability.CHAT_WRITE.impliedCapabilities());
        assertEquals(Set.of(IamCapability.CHAT_WRITE, IamCapability.CHAT_READ),
                IamCapability.expand(Set.of(IamCapability.CHAT_WRITE)));
        assertEquals(Set.of(IamCapability.CHAT_READ),
                IamCapability.expand(Set.of(IamCapability.CHAT_READ)));
    }

    @Test
    void expandsCentralImplicationsTransitivelyWithoutInventingGrants() {
        assertEquals(
                Set.of(IamCapability.GROUPS_MANAGE, IamCapability.GROUPS_READ),
                IamCapability.expand(Set.of(IamCapability.GROUPS_MANAGE))
        );
        assertEquals(
                Set.of(IamCapability.SOURCES_MANAGE, IamCapability.SOURCES_READ, IamCapability.SOURCES_DELETE),
                IamCapability.expand(Set.of(IamCapability.SOURCES_MANAGE))
        );
        assertEquals(
                Set.of(IamCapability.SOURCES_DELETE, IamCapability.SOURCES_READ),
                IamCapability.expand(Set.of(IamCapability.SOURCES_DELETE))
        );
        assertEquals(
                Set.of(IamCapability.USERS_MANAGE),
                IamCapability.expand(Set.of(IamCapability.USERS_MANAGE))
        );
    }

    @Test
    void adminExpandsToEveryCapabilityWithoutExposingMutableGrants() {
        Set<IamCapability> expanded = IamCapability.expand(Set.of(IamCapability.SYSTEM_ADMIN));

        assertEquals(EnumSet.allOf(IamCapability.class), expanded);
        assertThrows(UnsupportedOperationException.class, () -> expanded.remove(IamCapability.SEARCH_READ));
    }

    @Test
    void adminImpliesEveryOtherCapabilityWithoutExposingMutableMetadata() {
        Set<IamCapability> implied = IamCapability.SYSTEM_ADMIN.impliedCapabilities();

        assertEquals(EnumSet.complementOf(EnumSet.of(IamCapability.SYSTEM_ADMIN)), implied);
        assertThrows(UnsupportedOperationException.class, () -> implied.add(IamCapability.SYSTEM_ADMIN));
        assertThrows(UnsupportedOperationException.class, () -> implied.remove(IamCapability.SEARCH_READ));
    }

    @Test
    void adminExpansionStillRejectsNullInputsAndNullCapabilities() {
        assertThrows(NullPointerException.class, () -> IamCapability.expand(null));
        assertThrows(NullPointerException.class,
                () -> IamCapability.expand(Arrays.asList(IamCapability.SYSTEM_ADMIN, null)));
        assertThrows(NullPointerException.class,
                () -> IamCapability.expand(Arrays.asList(null, IamCapability.SYSTEM_ADMIN)));
    }
}
