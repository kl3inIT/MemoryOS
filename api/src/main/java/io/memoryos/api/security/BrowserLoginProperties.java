package io.memoryos.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

@ConfigurationProperties("memoryos.browser")
record BrowserLoginProperties(String registrationId) {

    BrowserLoginProperties {
        Assert.hasText(registrationId, "memoryos.browser.registration-id must not be blank");
    }
}
