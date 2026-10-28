package io.memoryos.api.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SecurityConfigurationTest {

    private static final IdentityProperties IDENTITY_PROPERTIES = new IdentityProperties("memoryos-api");

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    /** Plain HTTP is accepted only on the literal loopback address, which tests use; a loopback name is not enough. */
    @ParameterizedTest
    @CsvSource({
            "http://keys.example.test/jwks, false",
            "http://localhost:8080/jwks, false",
            "https://keys.example.test/jwks, true",
            "http://127.0.0.1:8080/jwks, true",
    })
    void acceptsAJwkSetUriOnlyOverHttpsOrLiteralLoopback(String jwkSetUri, boolean accepted) {
        if (accepted) {
            assertDoesNotThrow(() -> configuration.jwtDecoder("https://issuer.example.test", jwkSetUri, IDENTITY_PROPERTIES));
        } else {
            assertThrows(IllegalArgumentException.class,
                    () -> configuration.jwtDecoder("https://issuer.example.test", jwkSetUri, IDENTITY_PROPERTIES));
        }
    }
}
