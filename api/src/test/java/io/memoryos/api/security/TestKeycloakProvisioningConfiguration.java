package io.memoryos.api.security;

import io.memoryos.identity.KeycloakRecipientProvisioner;
import io.memoryos.identity.KeycloakRecipientProvisioning;

import java.util.Objects;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
class TestKeycloakProvisioningConfiguration {

    @Bean
    @Primary
    KeycloakRecipientProvisioner testKeycloakRecipientProvisioner() {
        return (email, expiresAt) -> {
            Objects.requireNonNull(email);
            Objects.requireNonNull(expiresAt);
            return KeycloakRecipientProvisioning.ACTIVATION_EMAIL_SENT;
        };
    }
}
