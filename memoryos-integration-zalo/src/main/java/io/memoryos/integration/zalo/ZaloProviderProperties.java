package io.memoryos.integration.zalo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param baseUrl the Zalo Bot Platform's address; a test points it at a local server */
@ConfigurationProperties("memoryos.zalo")
public record ZaloProviderProperties(@DefaultValue("https://bot-api.zaloplatforms.com") String baseUrl) {
    public ZaloProviderProperties {
        if (!baseUrl.matches("https?://[^\\s/?#]+(/[^\\s?#]*)?")) {
            throw new IllegalArgumentException("Invalid Zalo configuration: memoryos.zalo.base-url");
        }
    }
}
