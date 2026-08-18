package io.memoryos.api.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecurityConfigurationTest {

    private static final MemoryOsIdentityProperties IDENTITY_PROPERTIES = new MemoryOsIdentityProperties(
            "memoryos-api",
            List.of(new MemoryOsIdentityProperties.Binding(
                    "https://issuer.example.test",
                    "subject",
                    UUID.fromString("00000000-0000-0000-0000-000000000001"))));

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void rejectsPlainHttpJwkSetUriOutsideLiteralLoopback() {
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.jwtDecoder(
                        "https://issuer.example.test",
                        "http://keys.example.test/jwks",
                        IDENTITY_PROPERTIES));
    }

    @Test
    void rejectsLocalhostNameForPlainHttpJwkSetUri() {
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.jwtDecoder(
                        "https://issuer.example.test",
                        "http://localhost:8080/jwks",
                        IDENTITY_PROPERTIES));
    }

    @Test
    void acceptsHttpsJwkSetUri() {
        assertDoesNotThrow(() -> configuration.jwtDecoder(
                "https://issuer.example.test",
                "https://keys.example.test/jwks",
                IDENTITY_PROPERTIES));
    }

    @Test
    void acceptsLiteralLoopbackHttpJwkSetUriForTests() {
        assertDoesNotThrow(() -> configuration.jwtDecoder(
                "https://issuer.example.test",
                "http://127.0.0.1:8080/jwks",
                IDENTITY_PROPERTIES));
    }
}
