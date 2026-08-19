package io.memoryos.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.browser")
record MemoryOsBrowserProperties(String registrationId) {

    MemoryOsBrowserProperties {
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("memoryos.browser.registration-id must not be blank");
        }
    }
}
