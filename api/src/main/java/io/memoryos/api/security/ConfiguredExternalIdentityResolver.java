package io.memoryos.api.security;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ConfiguredExternalIdentityResolver implements ExternalIdentityResolver {

    private final Map<ExternalIdentity, ActorId> bindings;

    ConfiguredExternalIdentityResolver(MemoryOsIdentityProperties properties) {
        var configuredBindings = new HashMap<ExternalIdentity, ActorId>();
        for (var binding : properties.bindings()) {
            var identity = new ExternalIdentity(binding.issuer(), binding.subject());
            var previous = configuredBindings.putIfAbsent(identity, new ActorId(binding.actorId()));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate external identity binding for issuer and subject");
            }
        }
        bindings = Map.copyOf(configuredBindings);
    }

    @Override
    public Optional<ActorId> resolve(ExternalIdentity identity) {
        return Optional.ofNullable(bindings.get(Objects.requireNonNull(identity, "identity must not be null")));
    }
}
