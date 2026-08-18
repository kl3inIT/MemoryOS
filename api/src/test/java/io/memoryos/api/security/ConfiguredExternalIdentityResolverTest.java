package io.memoryos.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfiguredExternalIdentityResolverTest {

    private static final UUID ACTOR_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void resolvesSameSubjectSeparatelyForEachIssuer() {
        var resolver = new ConfiguredExternalIdentityResolver(properties(
                binding("https://issuer-a.example", "same-subject", ACTOR_A),
                binding("https://issuer-b.example", "same-subject", ACTOR_B)));

        assertEquals(
                new ActorId(ACTOR_A),
                resolver.resolve(new ExternalIdentity("https://issuer-a.example", "same-subject")).orElseThrow());
        assertEquals(
                new ActorId(ACTOR_B),
                resolver.resolve(new ExternalIdentity("https://issuer-b.example", "same-subject")).orElseThrow());
    }

    @Test
    void doesNotResolveUnknownIdentity() {
        var resolver = new ConfiguredExternalIdentityResolver(properties(
                binding("https://issuer.example", "known", ACTOR_A)));

        assertTrue(resolver.resolve(new ExternalIdentity("https://issuer.example", "unknown")).isEmpty());
    }

    @Test
    void rejectsDuplicateIssuerAndSubject() {
        var duplicate = properties(
                binding("https://issuer.example", "subject", ACTOR_A),
                binding("https://issuer.example", "subject", ACTOR_B));

        assertThrows(IllegalArgumentException.class, () -> new ConfiguredExternalIdentityResolver(duplicate));
    }

    private static MemoryOsIdentityProperties properties(MemoryOsIdentityProperties.Binding... bindings) {
        return new MemoryOsIdentityProperties("memoryos-api", List.of(bindings));
    }

    private static MemoryOsIdentityProperties.Binding binding(String issuer, String subject, UUID actorId) {
        return new MemoryOsIdentityProperties.Binding(issuer, subject, actorId);
    }
}
