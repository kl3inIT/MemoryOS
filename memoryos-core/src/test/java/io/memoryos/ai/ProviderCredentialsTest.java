package io.memoryos.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderCredentialsTest {
    private final ProviderCredentials credentials = new ProviderCredentials(Base64.getEncoder().encodeToString(new byte[32]));
    private final UUID tenant = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();

    @Test
    void encryptsWithDifferentIvsAndBindsCiphertextToTenantAndProvider() {
        var change = new ProviderCredentials.Change(ProviderCredentials.Action.REPLACE, "byok-secret");
        String encrypted = credentials.update(tenant, provider, null, change);
        assertNotNull(encrypted);
        assertFalse(encrypted.contains("byok-secret"));
        assertNotEquals(encrypted, credentials.update(tenant, provider, null, change));
        assertEquals("byok-secret", credentials.resolve(tenant, provider, encrypted));
        assertThrows(AiException.class, () -> credentials.resolve(UUID.randomUUID(), provider, encrypted));
        assertThrows(AiException.class, () -> credentials.resolve(tenant, UUID.randomUUID(), encrypted));
        String tampered = encrypted.substring(0, encrypted.length() - 2) + (encrypted.endsWith("00") ? "01" : "00");
        assertThrows(AiException.class, () -> credentials.resolve(tenant, provider, tampered));
        assertFalse(change.toString().contains("byok-secret"));
    }

    @Test
    void keepRemoveAndMissingMasterKeyAreExplicit() {
        var stored = credentials.update(tenant, provider, null, new ProviderCredentials.Change(ProviderCredentials.Action.REPLACE, "secret"));
        assertEquals(stored, credentials.update(tenant, provider, stored, new ProviderCredentials.Change(ProviderCredentials.Action.KEEP, null)));
        assertNull(credentials.update(tenant, provider, stored, new ProviderCredentials.Change(ProviderCredentials.Action.REMOVE, null)));
        assertFalse(credentials.configured(null));
        assertThrows(AiException.class, () -> credentials.update(tenant, provider, null, new ProviderCredentials.Change(ProviderCredentials.Action.KEEP, "ignored-secret")));
        var withoutKey = new ProviderCredentials("");
        assertThrows(AiException.class, () -> withoutKey.update(tenant, provider, null, new ProviderCredentials.Change(ProviderCredentials.Action.REPLACE, "secret")));
        assertThrows(AiException.class, () -> withoutKey.resolve(tenant, provider, stored));
    }

    @Test
    void theDeploymentMarkerIsNoLongerAKey() {
        // MEM-211: the seeded provider's key was the deployment's; a stored marker now resolves to nothing usable.
        assertThrows(AiException.class, () -> credentials.resolve(tenant, provider, "deployment"));
    }
}
