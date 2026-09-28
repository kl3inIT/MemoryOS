package io.memoryos.api.security;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.identity.jit")
public record JitAdmissionProperties(Set<String> allowedProviderAliases) {

    public JitAdmissionProperties {
        allowedProviderAliases = allowedProviderAliases == null ? Set.of() : Set.copyOf(allowedProviderAliases);
    }
}
