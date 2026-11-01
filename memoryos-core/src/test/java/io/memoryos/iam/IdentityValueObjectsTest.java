package io.memoryos.iam;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IdentityValueObjectsTest {

    @Test
    void externalIdentityRejectsBlankIssuerAndSubject() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalIdentity(" ", "subject"));
        assertThrows(IllegalArgumentException.class, () -> new ExternalIdentity("https://issuer.example", " "));
    }

}
