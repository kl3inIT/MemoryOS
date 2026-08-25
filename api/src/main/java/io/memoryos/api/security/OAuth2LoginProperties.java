package io.memoryos.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.browser")
record OAuth2LoginProperties(String registrationId) {

    OAuth2LoginProperties {
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("memoryos.browser.registration-id must not be blank");
        }
    }
}
