package io.memoryos.api.chat;

import com.embabel.agent.openai.CapabilityAwareOpenAiOptionsConverter;
import com.embabel.agent.openai.ModelCapabilities;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.OptionsConverter;
import com.embabel.common.ai.model.PricingModel;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ChatProviderAdapter;
import io.memoryos.chat.catalog.ModelCatalogService;
import io.memoryos.chat.catalog.ModelSettings;
import io.memoryos.chat.execution.ChatModelBinding;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** OpenAI Chat Completions adapter. Hosted web/image tools are separate integrations. */
public final class OpenAiChatProviderAdapter implements ChatProviderAdapter {
    private static final Set<String> OPTIONS = Set.of("maxCompletionTokens", "temperature", "topP", "frequencyPenalty", "presencePenalty", "reasoningEffort", "helperReasoningEffort", "webSearch", "reasoningSummary");
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    public OpenAiChatProviderAdapter(ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations;
        this.meters = meters;
    }
    @Override public String type() { return "openai"; }
    @Override public CredentialRequirement credentialRequirement() { return CredentialRequirement.REQUIRED; }
    @Override public List<TokenizerProfile> tokenizerProfiles() { return ChatTokenizerProfiles.METADATA; }
    @Override public List<KnownModel> knownModels() { return ChatKnownModels.models(); }
    @Override public boolean nativeWebSearch() { return true; }

    @Override public void validate(String baseUrl, String modelName, ModelSettings settings) {
        ModelCatalogService.validateEndpoint(baseUrl);
        ChatTokenizerProfiles.validate(settings);
        if (modelName == null || modelName.isBlank() || modelName.length() > 200 || !settings.capabilities().streaming())
            throw ChatException.invalid("Invalid streaming model configuration.");
        var options = settings.options();
        if (!OPTIONS.containsAll(options.keySet())) throw ChatException.invalid("Unsupported OpenAI model option.");
        if (options.containsKey("maxCompletionTokens") && !(options.get("maxCompletionTokens") instanceof Boolean))
            throw ChatException.invalid("maxCompletionTokens must be a boolean.");
        boolean completionTokens = Boolean.TRUE.equals(options.get("maxCompletionTokens"));
        if (completionTokens && options.keySet().stream().anyMatch(Set.of("temperature", "topP", "frequencyPenalty", "presencePenalty")::contains))
            throw ChatException.invalid("The verified completion-token options family does not accept sampling overrides.");
        number(options, "temperature", 0, 2);
        number(options, "topP", 0, 1);
        number(options, "frequencyPenalty", -2, 2);
        number(options, "presencePenalty", -2, 2);
        Object reasoning = options.get("reasoningEffort");
        if (reasoning != null && (!settings.capabilities().reasoning() || !(reasoning instanceof String)
                || !Set.of("none", "minimal", "low", "medium", "high").contains(reasoning)))
            throw ChatException.invalid("Unsupported reasoning effort for this model.");
        Object helperReasoning = options.get("helperReasoningEffort");
        if (helperReasoning != null && (!settings.capabilities().reasoning()
                || !(helperReasoning instanceof String)
                || !Set.of("none", "minimal", "low").contains(helperReasoning)))
            throw ChatException.invalid("Unsupported helper reasoning effort for this model.");
        Object summary = options.get("reasoningSummary");
        if (summary != null && (!"auto".equals(summary) || !settings.capabilities().reasoning()))
            throw ChatException.invalid("Reasoning summaries require the value auto and a reasoning model.");
        Object webSearch = options.get("webSearch");
        if (webSearch != null && (!"native".equals(webSearch) || !settings.capabilities().toolCalling()))
            throw ChatException.invalid("Native Web search requires the value native and a tool-capable model.");
    }

    @Override public boolean supportsNativeWebSearch(ModelSettings settings) {
        return "native".equals(settings.options().get("webSearch")) && settings.capabilities().toolCalling();
    }

    private static final int MAX_MODEL_LIST_BYTES = 1_048_576;

    @Override
    public List<String> reportedModels(Connection connection, Duration timeout) {
        ModelCatalogService.validateEndpoint(connection.baseUrl());
        if (connection.credential().isBlank()) throw ChatException.providerUnavailable();
        // A configured endpoint must not redirect this credential elsewhere, and its body is bounded.
        try (var client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(timeout).build()) {
            var request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(connection.baseUrl().replaceAll("/+$", "") + "/models"))
                    .timeout(timeout).header("Accept", "application/json")
                    .header("Authorization", "Bearer " + connection.credential()).GET().build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300) throw ChatException.providerUnavailable();
            byte[] body;
            try (var stream = response.body()) { body = stream.readNBytes(MAX_MODEL_LIST_BYTES + 1); }
            if (body.length == 0 || body.length > MAX_MODEL_LIST_BYTES) throw ChatException.providerUnavailable();
            var names = new java.util.ArrayList<String>();
            for (var item : new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data")) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) names.add(id);
                if (names.size() >= 500) break;
            }
            return names;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ChatException.providerUnavailable();
        } catch (ChatException expected) {
            throw expected;
        } catch (java.io.IOException | RuntimeException failure) {
            // The provider payload may carry account detail; report unavailability instead.
            throw ChatException.providerUnavailable();
        }
    }

    @Override
    public Client create(Connection connection, String modelName, ModelSettings settings, Duration timeout) {
        validate(connection.baseUrl(), modelName, settings);
        if (connection.credential().isBlank()) throw ChatException.providerUnavailable();
        var sync = OpenAIOkHttpClient.builder().baseUrl(connection.baseUrl()).apiKey(connection.credential())
                .maxRetries(0).timeout(timeout).build();
        try {
            var async = asyncClient(connection.baseUrl(), connection.credential(), timeout);
            try {
                // Hosted Web search and displayable reasoning summaries are Responses API features.
                boolean hostedSearch = supportsNativeWebSearch(settings);
                boolean summaries = "auto".equals(settings.options().get("reasoningSummary"));
                var model = hostedSearch || summaries
                        ? async.decorateNative(view -> new OpenAiResponsesChatModel(
                                OpenAiChatModel.builder().openAiClient(sync).openAiClientAsync(view)
                                        .options(OpenAiChatOptions.builder().apiKey(connection.credential()).maxRetries(0).build())
                                        .observationRegistry(observations).meterRegistry(meters).build(),
                                view, settings.capabilities().reasoning(), hostedSearch, summaries, meters))
                        // Only the Chat Completions route carries the tools-with-reasoning constraint.
                        : new OpenAiReasoningFallback(async.decorate(view -> OpenAiChatModel.builder()
                                .openAiClient(sync).openAiClientAsync(view)
                                .options(OpenAiChatOptions.builder().apiKey(connection.credential()).maxRetries(0).build())
                                .observationRegistry(observations).meterRegistry(meters).build()));
                return new Client(binding(modelName, settings, model, ChatTokenizerProfiles.hostedTokens()),
                        () -> { try { async.close(); } finally { sync.close(); } });
            } catch (RuntimeException | Error failure) { async.close(); throw failure; }
        } catch (RuntimeException | Error failure) { sync.close(); throw failure; }
    }

    static ChatModelBinding binding(String name, ModelSettings settings, ChatModel model, TokenCountEstimator tokens) {
        ChatTokenizerProfiles.validate(settings);
        boolean completionTokens = Boolean.TRUE.equals(settings.options().get("maxCompletionTokens"));
        var nativeConverter = new CapabilityAwareOpenAiOptionsConverter(completionTokens ? ModelCapabilities.GPT5_FAMILY : ModelCapabilities.DEFAULT);
        OptionsConverter converter = (options, modelName) -> {
            var effective = options.withTemperature(null);
            var configured = settings.options();
            if (configured.get("temperature") instanceof Number value) effective = effective.withTemperature(value.doubleValue());
            if (configured.get("topP") instanceof Number value) effective = effective.withTopP(value.doubleValue());
            if (configured.get("frequencyPenalty") instanceof Number value) effective = effective.withFrequencyPenalty(value.doubleValue());
            if (configured.get("presencePenalty") instanceof Number value) effective = effective.withPresencePenalty(value.doubleValue());
            var converted = (OpenAiChatOptions) nativeConverter.convertOptions(effective, modelName);
            if (configured.get("reasoningEffort") instanceof String effort) converted = converted.mutate().reasoningEffort(effort).build();
            if (settings.capabilities().reasoning() && options.getThinking() != null && !options.getThinking().getEnabled()) {
                // This override is applied after binding options. The verified GPT-5 mini baseline supports
                // minimal, not none; other model configurations declare their supported lowest effort.
                String effort = (String) configured.getOrDefault("helperReasoningEffort", "minimal");
                converted = converted.mutate().reasoningEffort(effort).build();
            }
            return converted;
        };
        var price = settings.pricing();
        var service = new SpringAiLlmService(name, "OpenAI", model, converter, null, List.of(),
                price == null ? null : PricingModel.usdPer1MTokens(price.inputPerMillion(), price.outputPerMillion()), settings.capabilities().reasoning());
        return new ChatModelBinding(service, OpenAiChatRequestPolicy::withoutTools,
                OpenAiChatRequestPolicy.create(settings, tokens), settings.contextWindow(), settings.maxOutputTokens(),
                settings.capabilities().toolCalling(), settings.capabilities().vision());
    }

    static OpenAiCancellation asyncClient(String baseUrl, String credential, Duration timeout) {
        return new OpenAiCancellation(baseUrl, credential, timeout);
    }

    private static void number(Map<String, Object> options, String key, double min, double max) {
        Object value = options.get(key);
        if (value != null && (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max))
            throw ChatException.invalid("Invalid numeric OpenAI option.");
    }
}
