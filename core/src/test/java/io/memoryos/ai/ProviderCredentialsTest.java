package io.memoryos.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderCredentialsTest {
    private final ProviderCredentials credentials = new ProviderCredentials(Base64.getEncoder().encodeToString(new byte[32]), "deployment-secret");
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
    void keepRemoveDeploymentReferenceAndMissingMasterKeyAreExplicit() {
        var keep = new ProviderCredentials.Change(ProviderCredentials.Action.KEEP, null);
        assertEquals("deployment", credentials.update(tenant, provider, "deployment", keep));
        assertEquals("deployment-secret", credentials.resolve(tenant, provider, "deployment"));
        assertNull(credentials.update(tenant, provider, "deployment", new ProviderCredentials.Change(ProviderCredentials.Action.REMOVE, null)));
        assertFalse(credentials.configured(null));
        assertThrows(AiException.class, () -> credentials.update(tenant, provider, null, new ProviderCredentials.Change(ProviderCredentials.Action.KEEP, "ignored-secret")));
        var withoutKey = new ProviderCredentials("", "deployment-secret");
        assertEquals("deployment-secret", withoutKey.resolve(tenant, provider, "deployment"));
        assertThrows(AiException.class, () -> withoutKey.update(tenant, provider, null, new ProviderCredentials.Change(ProviderCredentials.Action.REPLACE, "secret")));
    }
}
