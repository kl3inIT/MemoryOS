package io.memoryos.chat.catalog;

import io.memoryos.chat.execution.ChatModelBinding;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public extension point: register a bean per protocol; keep SDK options out of the executor. */
public interface ChatProviderAdapter {
    String type();
    CredentialRequirement credentialRequirement();
    List<TokenizerProfile> tokenizerProfiles();

    /**
     * Published metadata for model names this protocol is known to serve, used to prefill the
     * administration form. Runtime behaviour always comes from the stored settings, never from here.
     */
    default List<KnownModel> knownModels() { return List.of(); }

    /** Whether this protocol can host provider-side Web search at all; per-model activation stays explicit. */
    default boolean nativeWebSearch() { return false; }

    /** Explicit per-model declaration of provider-hosted Web search; never inferred from a model name. */
    default boolean supportsNativeWebSearch(ModelSettings settings) { return false; }

    /**
     * Model names the connected endpoint reports. Contacting the provider is the point, so callers
     * treat a failure as provider unavailability and never echo the provider's payload.
     */
    default List<String> reportedModels(Connection connection, Duration timeout) {
        throw io.memoryos.chat.ChatException.invalid("This adapter cannot list provider models.");
    }

    /** Local validation only. Must not contact the model or echo credentials in errors. */
    void validate(String baseUrl, String modelName, ModelSettings settings);

    /**
     * Own all clients created here and disable automatic retries. {@code readTimeout} bounds connecting and each
     * read/write gap, never a whole streamed call: a Chat turn has no total deadline.
     */
    Client create(Connection connection, String modelName, ModelSettings settings, Duration readTimeout);

    enum CredentialRequirement { REQUIRED, OPTIONAL, NONE }

    record TokenizerProfile(String id, String displayName) {
        public TokenizerProfile {
            if (id == null || id.isBlank() || displayName == null || displayName.isBlank())
                throw new IllegalArgumentException("Invalid tokenizer profile metadata");
        }
    }

    record KnownModel(String modelName, int contextWindow, int maxOutputTokens,
                      ModelSettings.Capabilities capabilities, ModelSettings.Pricing pricing) {
        public KnownModel {
            if (modelName == null || modelName.isBlank() || modelName.length() > 200 || capabilities == null
                    || pricing == null || contextWindow < 256 || contextWindow > 10000000
                    || maxOutputTokens < 1 || maxOutputTokens >= contextWindow)
                throw new IllegalArgumentException("Invalid known model metadata");
        }
    }

    /** Secret is intentionally exposed only by an explicit method and never by toString(). */
    @SuppressWarnings("ClassCanBeRecord") // Avoid automatic record-component serialization of the secret.
    final class Connection {
        private final String baseUrl;
        private final String credential;
        public Connection(String baseUrl, String credential) {
            this.baseUrl = baseUrl;
            this.credential = credential;
        }
        public String baseUrl() { return baseUrl; }
        public String credential() { return credential; }
        @Override public String toString() { return "Connection[redacted]"; }
    }

    final class Client implements AutoCloseable {
        private final ChatModelBinding binding;
        private final Runnable cleanup;
        private final AtomicBoolean closed = new AtomicBoolean();
        public Client(ChatModelBinding binding, Runnable cleanup) {
            this.binding = java.util.Objects.requireNonNull(binding);
            this.cleanup = java.util.Objects.requireNonNull(cleanup);
        }
        public ChatModelBinding binding() { return binding; }
        @Override public void close() { if (closed.compareAndSet(false, true)) cleanup.run(); }
    }
}
