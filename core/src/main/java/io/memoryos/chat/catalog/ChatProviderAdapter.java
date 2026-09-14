package io.memoryos.chat.catalog;

import io.memoryos.chat.execution.ChatModelBinding;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public extension point: register a bean per protocol; keep SDK options out of the executor. */
public interface ChatProviderAdapter {
    String type();
    CredentialRequirement credentialRequirement();

    /** Protocol support for forcing a named tool on the first request, not native Web search. */
    default boolean supportsRequiredToolChoice() { return false; }

    /** Whether this protocol can host provider-side Web search at all; per-model activation stays explicit. */
    default boolean nativeWebSearch() { return false; }

    /** Explicit per-model declaration of provider-hosted Web search; never inferred from a model name. */
    default boolean supportsNativeWebSearch(ModelSettings settings) { return false; }

    /** Local validation only. Must not contact the model or echo credentials in errors. */
    void validate(String baseUrl, String modelName, ModelSettings settings);

    /** Own all clients created here; disable automatic retries and apply the supplied deadline. */
    Client create(Connection connection, String modelName, ModelSettings settings, Duration timeout);

    enum CredentialRequirement { REQUIRED, OPTIONAL, NONE }

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
