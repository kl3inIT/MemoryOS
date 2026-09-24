package io.memoryos.chat.catalog.openai;

import com.embabel.agent.openai.CapabilityAwareOpenAiOptionsConverter;
import com.embabel.agent.openai.ModelCapabilities;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.OptionsConverter;
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

    /** OpenRouter's list with descriptions is about 2 MiB; the cap still bounds a hostile endpoint. */
    private static final int MAX_MODEL_LIST_BYTES = 16 * 1_048_576;
    private static final int MAX_REPORTED_MODELS = 1000;

    @Override public boolean listsModels() { return true; }

    @Override
    public List<ReportedModel> reportedModels(Connection connection, Duration timeout) {
        ModelCatalogService.validateEndpoint(connection.baseUrl());
        if (connection.credential().isBlank()) throw ChatException.invalid("Enter the provider API key.");
        // A configured endpoint must not redirect this credential elsewhere, and its body is bounded.
        try (var client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(timeout).build()) {
            var request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(connection.baseUrl().replaceAll("/+$", "") + "/models"))
                    .timeout(timeout).header("Accept", "application/json")
                    .header("Authorization", "Bearer " + connection.credential()).GET().build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 401 || status == 403) throw ChatException.providerCredentialRejected();
            if (status >= 500) throw ChatException.providerUnreachable();
            if (status >= 300) throw ChatException.providerIncompatible();
            byte[] body;
            try (var stream = response.body()) { body = stream.readNBytes(MAX_MODEL_LIST_BYTES + 1); }
            if (body.length == 0 || body.length > MAX_MODEL_LIST_BYTES) throw ChatException.providerIncompatible();
            com.fasterxml.jackson.databind.JsonNode data;
            try {
                data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data");
            } catch (java.io.IOException notJson) {
                throw ChatException.providerIncompatible();
            }
            if (!data.isArray()) throw ChatException.providerIncompatible();
            var models = new java.util.ArrayList<ReportedModel>();
            for (var item : data) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) models.add(reported(id, item));
                if (models.size() >= MAX_REPORTED_MODELS) break;
            }
            // Ollama and LM Studio publish their limits only on their native APIs (Onyx per-provider fetchers).
            var local = LocalModelMetadata.recognize(connection.baseUrl(), data, models);
            return local == null ? models
                    : LocalModelMetadata.enrich(client, local, connection.baseUrl(), connection.credential(), timeout, models);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ChatException.providerUnreachable();
        } catch (ChatException expected) {
            throw expected;
        } catch (java.io.IOException | RuntimeException failure) {
            // Connection refused, DNS or timeout; the provider payload may carry account detail and is never echoed.
            throw ChatException.providerUnreachable();
        }
    }

    /**
     * The limits, capabilities and prices an OpenAI-compatible {@code /models} entry publishes. Field names follow the
     * vendors that publish them: OpenRouter ({@code context_length}, {@code top_provider.max_completion_tokens},
     * {@code supported_parameters}, {@code architecture.input_modalities}, per-token {@code pricing}), vLLM
     * ({@code max_model_len}), Groq ({@code context_window}, {@code max_completion_tokens}), Mistral
     * ({@code max_context_length}, {@code capabilities}), Anthropic ({@code max_input_tokens}, {@code max_tokens}) and
     * Gemini ({@code inputTokenLimit}, {@code outputTokenLimit}). Anything absent stays null.
     */
    static ReportedModel reported(String id, com.fasterxml.jackson.databind.JsonNode item) {
        Integer context = firstInt(item, "context_length", "max_model_len", "context_window", "max_context_length",
                "max_input_tokens", "input_token_limit", "inputTokenLimit");
        if (context == null) context = firstInt(item.path("top_provider"), "context_length");
        Integer output = firstInt(item.path("top_provider"), "max_completion_tokens");
        if (output == null) output = firstInt(item, "max_completion_tokens", "max_output_tokens", "max_tokens",
                "output_token_limit", "outputTokenLimit");
        var parameters = item.path("supported_parameters");
        var capabilities = item.path("capabilities");
        var modalities = item.path("architecture").path("input_modalities");
        Boolean tools = parameters.isArray() ? Boolean.valueOf(contains(parameters, "tools")) : flag(capabilities, "function_calling");
        Boolean reasoning = parameters.isArray() ? Boolean.valueOf(contains(parameters, "reasoning")) : flag(capabilities, "reasoning");
        Boolean vision = modalities.isArray() ? Boolean.valueOf(contains(modalities, "image")) : flag(capabilities, "vision");
        // 9Router reports capabilities.tools; Anthropic nests {supported} flags (image_input, thinking) and has no
        // tool flag; Gemini reports thinking.
        if (tools == null) tools = flag(capabilities, "tools");
        if (vision == null) vision = flag(capabilities.path("image_input"), "supported");
        if (reasoning == null) reasoning = flag(capabilities.path("thinking"), "supported");
        if (reasoning == null) reasoning = flag(item, "thinking");
        var pricing = pricing(item.path("pricing"));
        if (pricing == null) pricing = xaiPricing(item);
        return new ReportedModel(id, context, output, tools, vision, reasoning, pricing);
    }

    /**
     * OpenRouter prices per token as decimal strings ({@code prompt}, {@code completion}); Together per million tokens
     * ({@code input}, {@code output}). -1 or a missing value means variable or unpublished.
     */
    private static ModelSettings.@org.jspecify.annotations.Nullable Pricing pricing(com.fasterxml.jackson.databind.JsonNode pricing) {
        Double prompt = perToken(pricing.path("prompt"));
        Double completion = perToken(pricing.path("completion"));
        if (prompt != null && completion != null)
            return new ModelSettings.Pricing(round(prompt * 1_000_000), round(completion * 1_000_000));
        Double input = perToken(pricing.path("input"));
        Double output = perToken(pricing.path("output"));
        return input == null || output == null ? null : new ModelSettings.Pricing(round(input), round(output));
    }

    /** xAI prices in US cents per 100 million tokens. */
    private static ModelSettings.@org.jspecify.annotations.Nullable Pricing xaiPricing(com.fasterxml.jackson.databind.JsonNode item) {
        Double prompt = perToken(item.path("prompt_text_token_price"));
        Double completion = perToken(item.path("completion_text_token_price"));
        return prompt == null || completion == null ? null
                : new ModelSettings.Pricing(round(prompt / 10_000), round(completion / 10_000));
    }

    private static @org.jspecify.annotations.Nullable Double perToken(com.fasterxml.jackson.databind.JsonNode node) {
        if (!node.isTextual() && !node.isNumber()) return null;
        try {
            double value = Double.parseDouble(node.asText());
            return Double.isFinite(value) && value >= 0 ? value : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static double round(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(6, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static @org.jspecify.annotations.Nullable Integer firstInt(com.fasterxml.jackson.databind.JsonNode node, String... fields) {
        for (String field : fields) {
            var value = node.path(field);
            if (value.canConvertToInt() && value.asInt() > 0) return value.asInt();
        }
        return null;
    }

    private static @org.jspecify.annotations.Nullable Boolean flag(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.path(field).isBoolean() ? Boolean.valueOf(node.path(field).asBoolean()) : null;
    }

    private static boolean contains(com.fasterxml.jackson.databind.JsonNode array, String value) {
        for (var element : array) if (value.equals(element.asText())) return true;
        return false;
    }

    @Override
    public Client create(Connection connection, String modelName, ModelSettings settings, Duration readTimeout) {
        validate(connection.baseUrl(), modelName, settings);
        if (connection.credential().isBlank()) throw ChatException.providerUnavailable();
        var sync = OpenAIOkHttpClient.builder().baseUrl(connection.baseUrl()).apiKey(connection.credential())
                .maxRetries(0).timeout(OpenAiCancellation.gap(readTimeout)).build();
        try {
            var async = asyncClient(connection.baseUrl(), connection.credential(), readTimeout);
            try {
                // As Onyx, a model served by OpenAI itself always streams through the Responses API with reasoning
                // summaries; an OpenAI-compatible endpoint opts in with hosted Web search or configured summaries.
                boolean openAi = servedByOpenAi(connection.baseUrl());
                boolean hostedSearch = supportsNativeWebSearch(settings);
                boolean summaries = openAi || "auto".equals(settings.options().get("reasoningSummary"));
                var model = openAi || hostedSearch || summaries
                        ? async.decorateNative(view -> new OpenAiResponsesChatModel(
                                OpenAiChatModel.builder().openAiClient(sync).openAiClientAsync(view)
                                        .options(OpenAiChatOptions.builder().apiKey(connection.credential()).maxRetries(0).build())
                                        .observationRegistry(observations).meterRegistry(meters).build(),
                                view, settings.capabilities().reasoning(), hostedSearch, summaries, openAi, meters))
                        // Only the Chat Completions route carries the tools-with-reasoning constraint; its providers
                        // stream reasoning beside the answer, published to the turn as the Responses route does.
                        : new ChatCompletionsReasoning(new OpenAiReasoningFallback(async.decorate(view -> OpenAiChatModel.builder()
                                .openAiClient(sync).openAiClientAsync(view)
                                .options(OpenAiChatOptions.builder().apiKey(connection.credential()).maxRetries(0).build())
                                .observationRegistry(observations).meterRegistry(meters).build())));
                return new Client(binding(modelName, settings, model, ChatTokenizerProfiles.hostedTokens()),
                        () -> { try { async.close(); } finally { sync.close(); } });
            } catch (RuntimeException | Error failure) { async.close(); throw failure; }
        } catch (RuntimeException | Error failure) { sync.close(); throw failure; }
    }

    public static ChatModelBinding binding(String name, ModelSettings settings, ChatModel model, TokenCountEstimator tokens) {
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
                price == null ? null : io.memoryos.chat.catalog.ChatModelPricing.of(price), settings.capabilities().reasoning());
        return new ChatModelBinding(service, OpenAiChatRequestPolicy::withoutTools,
                OpenAiChatRequestPolicy.create(settings, tokens), settings.contextWindow(), settings.maxOutputTokens(),
                settings.capabilities().toolCalling(), settings.capabilities().vision(), OpenAiChatRequestPolicy::requireTools,
                (llmService, sampling) -> llmService.withOptionsConverter((requested, requestedModel) ->
                        OpenAiChatRequestPolicy.withSampling(
                                llmService.getOptionsConverter().convertOptions(requested, requestedModel),
                                requested, sampling, settings)));
    }

    /** Onyx {@code is_true_openai_model}: the OpenAI API host, not a compatible gateway reusing this adapter. */
    static boolean servedByOpenAi(String baseUrl) {
        try { return "api.openai.com".equalsIgnoreCase(java.net.URI.create(baseUrl).getHost()); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    static OpenAiCancellation asyncClient(String baseUrl, String credential, Duration readTimeout) {
        return new OpenAiCancellation(baseUrl, credential, readTimeout);
    }

    private static void number(Map<String, Object> options, String key, double min, double max) {
        Object value = options.get(key);
        if (value != null && (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max))
            throw ChatException.invalid("Invalid numeric OpenAI option.");
    }
}
