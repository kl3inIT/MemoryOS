package io.memoryos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpSecretsTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final McpSecrets secrets = new McpSecrets(KEY);
    private final UUID tenant = UUID.randomUUID();
    private final UUID record = UUID.randomUUID();

    @Test
    void sealedValueOpensOnlyForItsTenantRecordAndPurpose() {
        String sealed = secrets.seal(tenant, record, McpSecrets.Purpose.CREDENTIAL, "{\"access_token\":\"secret\"}");

        assertTrue(sealed.startsWith("v1:"));
        assertFalse(sealed.contains("secret"));
        assertEquals("{\"access_token\":\"secret\"}", secrets.open(tenant, record, McpSecrets.Purpose.CREDENTIAL, sealed));
        assertUnreadable(() -> secrets.open(UUID.randomUUID(), record, McpSecrets.Purpose.CREDENTIAL, sealed));
        assertUnreadable(() -> secrets.open(tenant, UUID.randomUUID(), McpSecrets.Purpose.CREDENTIAL, sealed));
        assertUnreadable(() -> secrets.open(tenant, record, McpSecrets.Purpose.OAUTH_CLIENT_SECRET, sealed));
    }

    @Test
    void eachSealUsesAFreshIvAndTamperingFailsClosed() {
        String first = secrets.seal(tenant, record, McpSecrets.Purpose.SERVER_HEADERS, "value");
        String second = secrets.seal(tenant, record, McpSecrets.Purpose.SERVER_HEADERS, "value");
        assertNotEquals(first, second);

        char last = first.charAt(first.length() - 1);
        String tampered = first.substring(0, first.length() - 1) + (last == '0' ? '1' : '0');
        assertUnreadable(() -> secrets.open(tenant, record, McpSecrets.Purpose.SERVER_HEADERS, tampered));
        assertUnreadable(() -> secrets.open(tenant, record, McpSecrets.Purpose.SERVER_HEADERS, "v2:00"));
        assertUnreadable(() -> secrets.open(tenant, record, McpSecrets.Purpose.SERVER_HEADERS, "not-sealed"));
    }

    @Test
    void anotherKeyCannotOpenAValue() {
        String sealed = secrets.seal(tenant, record, McpSecrets.Purpose.CREDENTIAL, "value");
        byte[] other = new byte[32];
        other[0] = 1;
        var rotated = new McpSecrets(Base64.getEncoder().encodeToString(other));

        assertUnreadable(() -> rotated.open(tenant, record, McpSecrets.Purpose.CREDENTIAL, sealed));
    }

    @Test
    void missingKeyDisablesSealingAndOpeningAndInvalidKeysAreRejected() {
        var unconfigured = new McpSecrets("");

        assertFalse(unconfigured.configured());
        assertEquals("MCP_NOT_CONFIGURED", assertThrows(McpException.class,
                () -> unconfigured.seal(tenant, record, McpSecrets.Purpose.CREDENTIAL, "value")).code());
        assertEquals("MCP_NOT_CONFIGURED", assertThrows(McpException.class,
                () -> unconfigured.open(tenant, record, McpSecrets.Purpose.CREDENTIAL, "v1:00")).code());
        assertThrows(IllegalArgumentException.class, () -> new McpSecrets("not base64!"));
        assertThrows(IllegalArgumentException.class, () -> new McpSecrets(Base64.getEncoder().encodeToString(new byte[16])));
        assertThrows(IllegalArgumentException.class, () -> secrets.seal(tenant, record, McpSecrets.Purpose.CREDENTIAL, ""));
    }

    private static void assertUnreadable(org.junit.jupiter.api.function.Executable open) {
        assertEquals("MCP_CREDENTIAL_UNREADABLE", assertThrows(McpException.class, open).code());
    }
}
