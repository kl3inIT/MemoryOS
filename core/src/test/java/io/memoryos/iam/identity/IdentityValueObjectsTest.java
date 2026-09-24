package io.memoryos.iam.identity;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.identity.ExternalIdentity;
import io.memoryos.iam.identity.IdentityContext;

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
