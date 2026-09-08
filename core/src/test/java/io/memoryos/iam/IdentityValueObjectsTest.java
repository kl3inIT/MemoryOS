package io.memoryos.iam;

import static org.junit.jupiter.api.Assertions.assertThrows;

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

}
