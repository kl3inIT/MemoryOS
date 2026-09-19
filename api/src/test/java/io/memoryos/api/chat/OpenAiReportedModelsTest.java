package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memoryos.chat.catalog.ChatModelResolver;
import io.memoryos.chat.catalog.ChatProviderAdapter.ReportedModel;
import io.memoryos.chat.catalog.ModelSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each vendor publishes its limits under its own field names in an OpenAI-compatible /models entry (MEM-130). */
class OpenAiReportedModelsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ReportedModel parse(String json) throws Exception {
        var node = JSON.readTree(json);
        return OpenAiChatProviderAdapter.reported(node.path("id").asText(), node);
    }

    @Test
    void openRouterPublishesLimitsCapabilitiesAndPerTokenPrices() throws Exception {
        var model = parse("""
                {"id":"qwen/qwen3.8-27b","context_length":1000000,
                 "architecture":{"input_modalities":["text","image"]},
                 "pricing":{"prompt":"0.000000214","completion":"0.00000255"},
                 "top_provider":{"context_length":1000000,"max_completion_tokens":131072},
                 "supported_parameters":["tools","tool_choice","reasoning","max_tokens"]}""");
        assertEquals(1_000_000, model.contextWindow());
        assertEquals(131_072, model.maxOutputTokens());
        assertEquals(true, model.toolCalling());
        assertEquals(true, model.vision());
        assertEquals(true, model.reasoning());
        assertEquals(new ModelSettings.Pricing(0.214, 2.55), model.pricing());

        var textOnly = parse("""
                {"id":"x/free","context_length":8192,"architecture":{"input_modalities":["text"]},
                 "pricing":{"prompt":"-1","completion":"0"},"supported_parameters":["max_tokens"]}""");
        assertEquals(false, textOnly.toolCalling());
        assertEquals(false, textOnly.vision());
        assertNull(textOnly.pricing(), "a variable (-1) price is not a price");
        assertNull(textOnly.maxOutputTokens());
    }

    @Test
    void nineRouterReportsPerRouteLimitsAndCapabilities() throws Exception {
        // Live 9Router 0.5.81 on staging (2026-09-19): the Codex route caps gpt-5.6-luna at 272,000 tokens while the
        // OpenAI API serves 922,000, so the route's own limits are the ones to keep.
        var luna = parse("""
                {"id":"cx/gpt-5.6-luna","object":"model","owned_by":"cx","context_length":272000,
                 "max_completion_tokens":128000,
                 "capabilities":{"tools":true,"vision":true,"reasoning":true,"contextWindow":272000,"maxOutput":128000}}""");
        assertEquals(272_000, luna.contextWindow());
        assertEquals(128_000, luna.maxOutputTokens());
        assertEquals(true, luna.toolCalling());
        assertEquals(true, luna.vision());
        assertEquals(true, luna.reasoning());
        var spec = ChatModelResolver.spec(luna, ChatKnownModels.models());
        assertEquals(ChatModelResolver.ReportedModelSpec.Source.PROVIDER, spec.source());
        assertEquals(272_000, spec.contextWindow());
        // A 9Router combo names a routing rule, not a model: nothing is published, so Onyx's defaults apply.
        var combo = ChatModelResolver.spec(parse("{\"id\":\"oc\",\"object\":\"model\",\"owned_by\":\"combo\"}"),
                ChatKnownModels.models());
        assertEquals(ChatModelResolver.ReportedModelSpec.Source.NONE, combo.source());
        assertEquals(32_000, combo.contextWindow());
    }

    @Test
    void vllmGroqMistralAnthropicAndGeminiFieldsAreRead() throws Exception {
        assertEquals(32_768, parse("{\"id\":\"qwen\",\"max_model_len\":32768}").contextWindow());
        var groq = parse("{\"id\":\"llama\",\"context_window\":131072,\"max_completion_tokens\":32768}");
        assertEquals(131_072, groq.contextWindow());
        assertEquals(32_768, groq.maxOutputTokens());
        var mistral = parse("""
                {"id":"mistral-large-latest","max_context_length":131072,
                 "capabilities":{"function_calling":true,"vision":false}}""");
        assertEquals(131_072, mistral.contextWindow());
        assertEquals(true, mistral.toolCalling());
        assertEquals(false, mistral.vision());
        assertNull(mistral.reasoning());
        var anthropic = parse("""
                {"id":"claude-sonnet-4-5","max_input_tokens":200000,"max_tokens":64000,
                 "capabilities":{"image_input":{"supported":true},"thinking":{"supported":true}}}""");
        assertEquals(200_000, anthropic.contextWindow());
        assertEquals(64_000, anthropic.maxOutputTokens());
        assertEquals(true, anthropic.vision());
        assertEquals(true, anthropic.reasoning());
        assertNull(anthropic.toolCalling(), "Anthropic publishes no tool flag; the catalog decides");
        var xai = parse("""
                {"id":"grok-4","context_length":256000,"prompt_text_token_price":20000,"completion_text_token_price":150000}""");
        assertEquals(256_000, xai.contextWindow());
        assertEquals(new ModelSettings.Pricing(2.0, 15.0), xai.pricing());
        assertEquals(new ModelSettings.Pricing(0.88, 0.88),
                parse("{\"id\":\"llama\",\"context_length\":131072,\"pricing\":{\"input\":0.88,\"output\":0.88}}").pricing());
        var gemini = parse("{\"id\":\"models/gemini-2.5-pro\",\"inputTokenLimit\":1048576,\"outputTokenLimit\":65536,\"thinking\":true}");
        assertEquals(1_048_576, gemini.contextWindow());
        assertEquals(65_536, gemini.maxOutputTokens());
        assertEquals(true, gemini.reasoning());
        var openAi = parse("{\"id\":\"gpt-5-mini\",\"object\":\"model\",\"owned_by\":\"openai\"}");
        assertEquals(ReportedModel.named("gpt-5-mini"), openAi);
    }

    @Test
    void theInstalledCatalogFillsWhatTheEndpointDoesNotPublish() {
        var known = ChatKnownModels.models();
        // OpenAI and Gemini name models only: the catalog supplies limits, capabilities and prices by name.
        var openAi = ChatModelResolver.spec(ReportedModel.named("gpt-5-mini"), known);
        assertEquals(ChatModelResolver.ReportedModelSpec.Source.CATALOG, openAi.source());
        assertEquals(272_000, openAi.contextWindow());
        assertEquals(128_000, openAi.maxOutputTokens());
        assertTrue(openAi.capabilities().toolCalling());
        assertEquals(new ModelSettings.Pricing(0.25, 2.0, 0.025), openAi.pricing());
        assertEquals(ChatModelResolver.ReportedModelSpec.Source.CATALOG, ChatModelResolver.spec(ReportedModel.named("models/gemini-2.5-pro"), known).source());

        // OpenRouter's own answer limit, capabilities and prices win; its total window (400,000) exceeds OpenAI's
        // 272,000-token input cap, so the smaller catalog window is kept.
        var routed = ChatModelResolver.spec(new ReportedModel("openai/gpt-5-mini", 400_000, 100_000, true, false, true,
                new ModelSettings.Pricing(0.3, 2.1)), known);
        assertEquals(ChatModelResolver.ReportedModelSpec.Source.PROVIDER, routed.source());
        assertEquals(272_000, routed.contextWindow());
        assertEquals(100_000, routed.maxOutputTokens());
        assertFalse(routed.capabilities().vision());
        assertEquals(new ModelSettings.Pricing(0.3, 2.1), routed.pricing());

        // xAI publishes a context window and no answer limit: the limit is left empty, never guessed, and the model is
        // added as is so the provider's default applies (Onyx).
        var grok = ChatModelResolver.spec(new ReportedModel("grok-code-9", 256_000, null, true, false, true, null), known);
        assertEquals(256_000, grok.contextWindow());
        assertNull(grok.maxOutputTokens());
        // vLLM publishes only its context window: as Onyx, tools are assumed and vision and reasoning stay off.
        var local = ChatModelResolver.spec(new ReportedModel("local-qwen", 32_768, null, null, null, null, null), known);
        assertEquals(32_768, local.contextWindow());
        assertNull(local.maxOutputTokens());
        assertEquals(new ModelSettings.Capabilities(true, true, false, false), local.capabilities());

        // A name nobody describes gets Onyx's 32,000-token fallback window and is marked for review.
        var unknown = ChatModelResolver.spec(ReportedModel.named("acme-internal-7b"), known);
        assertEquals(ChatModelResolver.ReportedModelSpec.Source.NONE, unknown.source());
        assertEquals(32_000, unknown.contextWindow());
        assertNull(unknown.maxOutputTokens());
        assertNull(unknown.pricing());
    }

    @Test
    void aPublishedAnswerLimitAtOrAboveTheWindowFallsBackToTheCatalog() {
        var known = List.copyOf(ChatKnownModels.models());
        var spec = ChatModelResolver.spec(new ReportedModel("gpt-5-mini", 272_000, 272_000, null, null, null, null), known);
        assertEquals(128_000, spec.maxOutputTokens());
    }
}
