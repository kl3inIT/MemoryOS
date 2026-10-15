package io.memoryos.ai.anthropic;

import com.embabel.agent.anthropic.AnthropicOptionsConverter;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.OptionsConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memoryos.ai.AiException;
import io.memoryos.ai.KnownModels;
import io.memoryos.ai.ModelBinding;
import io.memoryos.ai.ModelCatalogService;
import io.memoryos.ai.ModelPricing;
import io.memoryos.ai.ModelSettings;
import io.memoryos.ai.ProviderAdapter;
import io.memoryos.ai.TokenizerProfiles;
import io.memoryos.shared.OutboundHttp;
import io.memoryos.shared.OutboundHttp.Limits;
import io.memoryos.shared.OutboundHttp.ResponseTooLargeException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.http.MediaType;

/**
 * Anthropic Messages API adapter. The base URL is the API root ({@code https://api.anthropic.com}); a Claude model
 * behind an OpenAI-compatible router stays on the {@code openai} adapter, which is the router's protocol.
 */
public final class AnthropicProviderAdapter implements ProviderAdapter {
    private static final Set<String> OPTIONS = Set.of("temperature", "topP", "reasoningEffort", AnthropicRequestPolicy.THINKING_BUDGET);
    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_MODEL_LIST_BYTES = 4 * 1_048_576;
    private static final int MAX_REPORTED_MODELS = 1000;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    public AnthropicProviderAdapter(ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations;
        this.meters = meters;
    }

    @Override public String type() { return "anthropic"; }
    @Override public CredentialRequirement credentialRequirement() { return CredentialRequirement.REQUIRED; }
    @Override public List<TokenizerProfile> tokenizerProfiles() { return TokenizerProfiles.HOSTED_METADATA; }
    @Override public List<KnownModel> knownModels() { return KnownModels.models(); }
    @Override public boolean listsModels() { return true; }

    @Override public void validate(String baseUrl, String modelName, ModelSettings settings) {
        ModelCatalogService.validateEndpoint(baseUrl);
        TokenizerProfiles.estimator(settings.tokenizerProfile());
        if (modelName == null || modelName.isBlank() || modelName.length() > 200 || !settings.capabilities().streaming())
            throw AiException.invalid("Invalid streaming model configuration.");
        var options = settings.options();
        if (!OPTIONS.containsAll(options.keySet())) throw AiException.invalid("Unsupported Anthropic model option.");
        number(options, "temperature");
        number(options, "topP");
        // The Messages API refuses a temperature beside thinking, so a reasoning model takes none.
        if (settings.capabilities().reasoning() && options.containsKey("temperature"))
            throw AiException.invalid("A reasoning model does not accept a temperature.");
        Object reasoning = options.get("reasoningEffort");
        if (reasoning != null && (!settings.capabilities().reasoning() || !(reasoning instanceof String)
                || !Set.of("none", "low", "medium", "high").contains(reasoning)))
            throw AiException.invalid("Unsupported reasoning effort for this model.");
        Object budget = options.get(AnthropicRequestPolicy.THINKING_BUDGET);
        if (budget != null && (!(budget instanceof Boolean) || !settings.capabilities().reasoning()))
            throw AiException.invalid("A thinking budget requires a boolean and a reasoning model.");
    }

    @Override
    public List<ReportedModel> reportedModels(Connection connection, Duration timeout) {
        ModelCatalogService.validateEndpoint(connection.baseUrl());
        if (connection.credential().isBlank()) throw AiException.invalid("Enter the provider API key.");
        // A configured endpoint must not redirect this credential elsewhere, and its body is bounded.
        var client = OutboundHttp.builder(new Limits(timeout, MAX_MODEL_LIST_BYTES)).build();
        try {
            byte[] body = client.get().uri(URI.create(root(connection.baseUrl()) + "/v1/models?limit=" + MAX_REPORTED_MODELS))
                    .accept(MediaType.APPLICATION_JSON)
                    .header("x-api-key", connection.credential()).header("anthropic-version", API_VERSION)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw AiException.providerCredentialRejected();
                        if (status >= 500) throw AiException.providerUnreachable();
                        if (status >= 300) throw AiException.providerIncompatible();
                        return response.getBody().readAllBytes();
                    });
            JsonNode data;
            try {
                data = new ObjectMapper().readTree(body).path("data");
            } catch (IOException notJson) {
                throw AiException.providerIncompatible();
            }
            if (!data.isArray()) throw AiException.providerIncompatible();
            var models = new ArrayList<ReportedModel>();
            for (var item : data) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) models.add(reported(id, item));
                if (models.size() >= MAX_REPORTED_MODELS) break;
            }
            return models;
        } catch (AiException expected) {
            throw expected;
        } catch (RuntimeException failure) {
            if (failure.getCause() instanceof ResponseTooLargeException) throw AiException.providerIncompatible();
            // Connection refused, DNS or timeout; the provider payload may carry account detail and is never echoed.
            throw AiException.providerUnreachable();
        }
    }

    /**
     * What a Models API entry publishes: {@code max_input_tokens}, {@code max_tokens} and nested {@code supported}
     * capability flags. It publishes no tool flag and no price; the installed catalog supplies both.
     */
    static ReportedModel reported(String id, JsonNode item) {
        var capabilities = item.path("capabilities");
        return new ReportedModel(id, positive(item.path("max_input_tokens")), positive(item.path("max_tokens")), null,
                supported(capabilities.path("image_input")), supported(capabilities.path("thinking")),
                supported(capabilities.path("structured_outputs")), null);
    }

    private static @Nullable Integer positive(JsonNode value) {
        return value.canConvertToInt() && value.asInt() > 0 ? Integer.valueOf(value.asInt()) : null;
    }

    private static @Nullable Boolean supported(JsonNode capability) {
        return capability.path("supported").isBoolean() ? Boolean.valueOf(capability.path("supported").asBoolean()) : null;
    }

    @Override
    public Client create(Connection connection, String modelName, ModelSettings settings, Duration readTimeout) {
        validate(connection.baseUrl(), modelName, settings);
        if (connection.credential().isBlank()) throw AiException.providerUnavailable();
        var clients = new AnthropicCancellation(root(connection.baseUrl()), connection.credential(), readTimeout);
        try {
            var model = new AnthropicTurnModel(clients, view -> AnthropicChatModel.builder()
                    .anthropicClient(clients.sync()).anthropicClientAsync(view)
                    .options(AnthropicChatOptions.builder().model(modelName).maxRetries(0).build())
                    .observationRegistry(observations).meterRegistry(meters).build());
            return new Client(binding(modelName, settings, model, TokenizerProfiles.estimator(settings.tokenizerProfile())),
                    clients::close);
        } catch (RuntimeException | Error failure) {
            clients.close();
            throw failure;
        }
    }

    static ModelBinding binding(String name, ModelSettings settings, ChatModel model, TokenCountEstimator tokens) {
        var configured = settings.options();
        OptionsConverter converter = (options, modelName) -> {
            // A helper call may name its own temperature; an answer's comes from the configuration and the turn's
            // creativity, and a reasoning model gets none.
            boolean helper = options.getThinking() != null && !options.getThinking().getEnabled();
            var effective = options.withTemperature(
                    helper && AnthropicRequestPolicy.takesTemperature(settings) ? options.getTemperature() : null);
            if (configured.get("temperature") instanceof Number value) effective = effective.withTemperature(value.doubleValue());
            if (configured.get("topP") instanceof Number value) effective = effective.withTopP(value.doubleValue());
            var converted = (AnthropicChatOptions) AnthropicOptionsConverter.INSTANCE.convertOptions(effective, modelName);
            if (settings.capabilities().reasoning()) {
                if (helper) converted = converted.mutate().thinkingDisabled().build();
                else if (configured.get("reasoningEffort") instanceof String effort)
                    converted = AnthropicRequestPolicy.thinking(converted, AnthropicRequestPolicy.level(effort), settings);
            }
            // A typed call on a model that declares structured output carries the schema of its answer.
            if (options.<Object>getExtension(ModelBinding.OUTPUT_SCHEMA) instanceof String schema)
                converted = converted.mutate().outputSchema(schema).build();
            return converted;
        };
        var price = settings.pricing();
        var service = new SpringAiLlmService(name, "Anthropic", model, converter, null, List.of(),
                price == null ? null : ModelPricing.of(price), settings.capabilities().reasoning());
        return ModelBinding.builder(service, AnthropicRequestPolicy::withoutTools,
                        AnthropicRequestPolicy.create(settings, tokens), settings.contextWindow(), settings.maxOutputTokens(),
                        settings.capabilities().toolCalling(), settings.capabilities().vision())
                .structuredOutput(settings.capabilities().structuredOutput())
                .requiredTools(AnthropicRequestPolicy::requireTools)
                .sampling((llmService, sampling) -> llmService.withOptionsConverter((requested, requestedModel) ->
                        AnthropicRequestPolicy.withSampling(
                                llmService.getOptionsConverter().convertOptions(requested, requestedModel),
                                requested, sampling, settings)))
                .failureReading(AnthropicFailures::read).build();
    }

    /** The API root; the SDK and the Models API add {@code /v1} themselves. */
    private static String root(String baseUrl) {
        return baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "");
    }

    private static void number(Map<String, Object> options, String key) {
        Object value = options.get(key);
        if (value != null && (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > 1))
            throw AiException.invalid("Invalid numeric Anthropic option.");
    }
}
