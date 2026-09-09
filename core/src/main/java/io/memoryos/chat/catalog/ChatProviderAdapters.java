package io.memoryos.chat.catalog;

import io.memoryos.chat.ChatException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Protocol dispatch only; product selection/access belongs to the catalog service. */
public final class ChatProviderAdapters {
    private final Map<String, ChatProviderAdapter> adapters;
    public ChatProviderAdapters(List<ChatProviderAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(ChatProviderAdapter::type, Function.identity()));
    }
    public ChatProviderAdapter require(String type) {
        var adapter = adapters.get(type);
        if (adapter == null) throw ChatException.invalid("Unsupported provider adapter.");
        return adapter;
    }
    public boolean supports(String type) { return adapters.containsKey(type); }
    public List<Descriptor> available() {
        return adapters.values().stream().map(a -> new Descriptor(a.type(), a.credentialRequirement()))
                .sorted(java.util.Comparator.comparing(Descriptor::type)).toList();
    }
    public record Descriptor(String type, ChatProviderAdapter.CredentialRequirement credentialRequirement) {}
}
