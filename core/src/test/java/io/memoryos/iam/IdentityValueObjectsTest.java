package io.memoryos.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityValueObjectsTest {

    @Test
    void actorIdRejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new ActorId(null));
    }

    @Test
    void externalIdentityRejectsBlankIssuerAndSubject() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalIdentity(" ", "subject"));
        assertThrows(IllegalArgumentException.class, () -> new ExternalIdentity("https://issuer.example", " "));
    }

    @Test
    void identityContextRejectsMissingActor() {
        assertThrows(NullPointerException.class, () -> new IdentityContext(null));
    }

    @Test
    void actorIdCarriesOpaqueUuid() {
        var value = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals(value, new ActorId(value).value());
    }
}
