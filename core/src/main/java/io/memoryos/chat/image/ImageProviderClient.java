package io.memoryos.chat.image;

import io.memoryos.shared.ActorId;
import io.memoryos.usage.AiUsage;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageRecorder;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Image generation and editing for Chat. The connection's adapter shapes each request; this client owns the
 * credential lookup, the endpoint default, usage records and one observation per provider call. Provider errors and
 * credentials never become model/UI output.
 */
@Component
@NamedInterface("image")
public final class ImageProviderClient {
    private final ImageConnectionService connections;
    private final ImageAdapterRegistry adapters;
    private final ImageCall call;
    private final ObservationRegistry observations;
    private final @Nullable AiUsageRecorder usage;

    public ImageProviderClient(ImageHttp http, ImageConnectionService connections, ImageAdapterRegistry adapters,
                               ObjectMapper json, ObservationRegistry observations, @Nullable AiUsageRecorder usage) {
        this.connections = connections; this.adapters = adapters; this.observations = observations; this.usage = usage;
        this.call = new ImageCall(http, json);
    }

    public record Result(byte[] bytes, String mediaType, @Nullable String revisedPrompt) {}

    /**
     * Adds one delivered image to the AI usage ledger. Image connections carry no price, so the cost is unknown;
     * connection probes are not recorded because only Chat tools call this.
     */
    public void recordImage(ImageConnectionService.Connection connection, @Nullable ActorId actor, boolean edit) {
        if (usage == null) return;
        String model = adapters.adapter(connection.provider()).usageModel(connection.model(), edit);
        usage.record(new AiUsage(connection.tenantId(), actor == null ? null : actor.value(),
                edit ? AiUsageFlow.IMAGE_EDIT : AiUsageFlow.IMAGE_GENERATION,
                connection.provider().name(), model, connection.id(), null, null, 1, 0, 0, 0, 1, 0, null, Instant.now()));
    }

    public Result generate(ImageConnectionService.Connection connection, String prompt, @Nullable String shape) throws IOException {
        return generate(connection, prompt, shape, null);
    }

    /** An override key authenticates an unsaved probe; null resolves the connection's stored credential. */
    public Result generate(ImageConnectionService.Connection connection, String prompt, @Nullable String shape, @Nullable String key) throws IOException {
        return measured(connection.provider().name(), "generate", () -> {
            validate(prompt);
            var adapter = adapters.adapter(connection.provider());
            // The tool shape maps to a declared size of the configured model; unknown models and models without
            // declared sizes keep the provider default.
            String size = adapter.capabilities().sizeFor(connection.model(), shape);
            return adapter.generate(call, base(adapter, connection), key != null ? key : connections.key(connection),
                    connection.model(), prompt, size);
        });
    }

    /** Edits a normalized working image from an English instruction; a mask is applied afterwards by the caller. */
    public Result edit(ImageConnectionService.Connection connection, String prompt, ImageEditImages.Working image) throws IOException {
        return measured(connection.provider().name(), "edit", () -> {
            validate(prompt);
            var adapter = adapters.adapter(connection.provider());
            return adapter.edit(call, base(adapter, connection), connections.key(connection), connection.model(), prompt, image);
        });
    }

    /** The configured endpoint without trailing slashes, else the provider's public API, else empty. */
    private static String base(ImageGenerationAdapter adapter, ImageConnectionService.Connection connection) {
        String base = connection.endpoint().replaceAll("/+$", "");
        String fallback = adapter.capabilities().defaultEndpoint();
        return base.isEmpty() && fallback != null ? fallback : base;
    }

    private static void validate(@Nullable String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000) throw new IllegalArgumentException("Invalid image prompt");
    }

    @FunctionalInterface private interface Request<T> { T run() throws IOException; }

    /**
     * One observation per provider call, {@code memoryos.chat.image.request} with bounded {@code provider},
     * {@code operation} and {@code outcome} keys; its timer is what the Chat & AI dashboard reads. Calls are not a
     * provider billing or token-usage estimate.
     */
    private <T> T measured(String provider, String operation, Request<T> request) throws IOException {
        var observation = Observation.createNotStarted("memoryos.chat.image.request", observations)
                .lowCardinalityKeyValue("provider", provider).lowCardinalityKeyValue("operation", operation);
        observation.start();
        String outcome = "failed";
        try (var _ = observation.openScope()) {
            T result = request.run();
            outcome = "succeeded";
            return result;
        } catch (IOException | RuntimeException failure) {
            // The exception type only: provider failures can carry request details.
            observation.error(new IllegalStateException(failure.getClass().getSimpleName()));
            throw failure;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).stop();
        }
    }
}
