package io.memoryos.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

class IamCapabilityTest {

    @Test
    void expandsCentralImplicationsTransitivelyWithoutInventingGrants() {
        assertEquals(
                Set.of(IamCapability.GROUPS_MANAGE, IamCapability.GROUPS_READ),
                IamCapability.expand(Set.of(IamCapability.GROUPS_MANAGE))
        );
        assertEquals(
                Set.of(IamCapability.SOURCES_MANAGE, IamCapability.SOURCES_READ),
                IamCapability.expand(Set.of(IamCapability.SOURCES_MANAGE))
        );
        assertEquals(
                Set.of(IamCapability.SOURCES_DELETE, IamCapability.SOURCES_READ),
                IamCapability.expand(Set.of(IamCapability.SOURCES_DELETE))
        );
        assertEquals(Set.of(IamCapability.values()), IamCapability.expand(Set.of(
                IamCapability.IAM_ADMIN
        )));
    }
}
