package io.memoryos.ai;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Protocol dispatch only; product selection/access belongs to the catalog service. */
public final class ProviderAdapters {
    private final Map<String, ProviderAdapter> adapters;
    public ProviderAdapters(List<ProviderAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(ProviderAdapter::type, Function.identity()));
    }
    public ProviderAdapter require(String type) {
        var adapter = adapters.get(type);
        if (adapter == null) throw AiException.invalid("Unsupported provider adapter.");
        return adapter;
    }
    public boolean supports(String type) { return adapters.containsKey(type); }
    public List<Descriptor> available() {
        return adapters.values().stream()
                .map(a -> new Descriptor(a.type(), a.credentialRequirement(), a.tokenizerProfiles(), a.nativeWebSearch(), a.knownModels()))
                .sorted(Comparator.comparing(Descriptor::type)).toList();
    }
    public record Descriptor(String type, ProviderAdapter.CredentialRequirement credentialRequirement,
                             List<ProviderAdapter.TokenizerProfile> tokenizerProfiles, boolean nativeWebSearch,
                             List<ProviderAdapter.KnownModel> knownModels) {
        public Descriptor {
            tokenizerProfiles = List.copyOf(tokenizerProfiles);
            knownModels = List.copyOf(knownModels);
        }
    }
}
