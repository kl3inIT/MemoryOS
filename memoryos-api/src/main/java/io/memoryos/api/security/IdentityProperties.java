package io.memoryos.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

@ConfigurationProperties("memoryos.identity")
public record IdentityProperties(String audience) {

    public IdentityProperties {
        Assert.hasText(audience, "memoryos.identity.audience must not be blank");
    }
}
