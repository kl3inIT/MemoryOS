package io.memoryos.integration.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param bridgePath the whatsmeow bridge binary, which the {@code channels} image carries; a process without it adds
 *        and checks WhatsApp bots but cannot open one
 * @param bridgeLogLevel the lowest level of the bridge's own log that reaches this process's log
 */
@ConfigurationProperties("memoryos.whatsapp")
public record WhatsAppProviderProperties(
        @DefaultValue("/application/bin/memoryos-whatsapp-bridge") String bridgePath,
        @DefaultValue("WARN") String bridgeLogLevel) {
    public WhatsAppProviderProperties {
        if (bridgePath.isBlank()) throw new IllegalArgumentException("Invalid WhatsApp configuration: memoryos.whatsapp.bridge-path");
        if (!bridgeLogLevel.matches("(?i)DEBUG|INFO|WARN|ERROR")) {
            throw new IllegalArgumentException("Invalid WhatsApp configuration: memoryos.whatsapp.bridge-log-level");
        }
    }
}
