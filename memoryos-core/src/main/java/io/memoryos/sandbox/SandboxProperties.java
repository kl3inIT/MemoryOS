package io.memoryos.sandbox;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The sandbox's deployment settings (MEM-270). An empty {@code computerImage} means the deployment has no sandbox.
 *
 * @param idleTimeout how long an unused sandbox lives; every use renews it (spring-agent: 30 minutes)
 * @param maxLifetime how long a sandbox lives at most before a fresh one replaces it (spring-agent: 4 hours)
 * @param maxRunning how many sandboxes may run at once on the deployment
 * @param allowedDomains the only domains a sandbox may reach; empty denies all egress
 */
@ConfigurationProperties("memoryos.sandbox")
public record SandboxProperties(@Nullable String computerImage, @DefaultValue("30m") Duration idleTimeout,
                                @DefaultValue("4h") Duration maxLifetime, @DefaultValue("3") int maxRunning,
                                @DefaultValue("1") String cpu, @DefaultValue("1Gi") String memory,
                                @Nullable List<String> allowedDomains) {
    public SandboxProperties {
        computerImage = computerImage == null ? "" : computerImage.strip();
        allowedDomains = allowedDomains == null ? List.of() : allowedDomains.stream().map(String::strip)
                .filter(domain -> !domain.isEmpty()).toList();
        if (idleTimeout.compareTo(Duration.ofMinutes(1)) < 0) throw new IllegalArgumentException("Sandbox idle timeout under a minute");
        if (maxLifetime.compareTo(idleTimeout) < 0) throw new IllegalArgumentException("Sandbox lifetime shorter than its idle timeout");
        if (maxRunning < 1) throw new IllegalArgumentException("Sandbox bound must be positive");
    }

    public boolean configured() {
        return !computerImage().isEmpty();
    }

    @Override
    public String computerImage() {
        return computerImage == null ? "" : computerImage;
    }

    @Override
    public List<String> allowedDomains() {
        return allowedDomains == null ? List.of() : allowedDomains;
    }
}
