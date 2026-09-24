package io.memoryos.chat.catalog.openai;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ModelSettings;
import io.memoryos.chat.execution.ChatRequestPolicy;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import com.embabel.common.ai.model.LlmOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** Native policy applied after Embabel has converted messages, tool callbacks and options. */
final class OpenAiChatRequestPolicy {
    private OpenAiChatRequestPolicy() {}

    static ChatRequestPolicy create(ModelSettings settings, TokenCountEstimator tokens) {
        var capabilities = settings.capabilities();
        return new ChatRequestPolicy(tokens,
                prompt -> hostedCount(prompt, tokens),
                prompt -> {
                    if (!(prompt.getOptions() instanceof OpenAiChatOptions options))
                        throw new IllegalArgumentException("CHAT_UNSUPPORTED_OPTIONS");
                    for (var message : prompt.getInstructions()) checkMessage(message, capabilities.toolCalling(), capabilities.vision());
                    boolean completionTokens = Boolean.TRUE.equals(settings.options().get("maxCompletionTokens"));
                    Integer requested = completionTokens ? options.getMaxCompletionTokens() : options.getMaxTokens();
                    Integer limit = settings.maxOutputTokens();
                    // Neither a request cap nor a published model limit: send none, so the provider default applies (Onyx).
                    Integer output = requested == null ? limit : limit == null ? requested : Integer.valueOf(Math.min(requested, limit));
                    if (output != null && output < 1) throw ChatException.invalid("Invalid output token limit.");
                    var builder = options.mutate().maxCompletionTokens(null).maxTokens(null);
                    if (output != null && completionTokens) builder.maxCompletionTokens(output);
                    else if (output != null) builder.maxTokens(output);
                    if (!capabilities.toolCalling()) builder.toolCallbacks(List.of()).toolContext(null).toolChoice(null).parallelToolCalls(null).strict(null);
                    if (!capabilities.reasoning()) builder.reasoningEffort(null);
                    return new Prompt(prompt.getInstructions(), builder.build());
                }, response -> checkResponse(response, capabilities.toolCalling(), capabilities.vision()));
    }

    /**
     * This turn's creativity and reasoning level, applied after the model configuration. A helper call (Embabel
     * thinking disabled) keeps the low effort the converter gave it; a model that does not reason takes no effort, and
     * a reasoning model takes no sampling temperature, which is the pairing the model editor already enforces.
     */
    static ChatOptions withSampling(ChatOptions converted, LlmOptions requested,
                                    io.memoryos.chat.ChatSampling sampling, ModelSettings settings) {
        if (sampling.isEmpty() || !(converted instanceof OpenAiChatOptions options)) return converted;
        boolean helper = requested.getThinking() != null && !requested.getThinking().getEnabled();
        if (helper) return converted;
        var configured = settings.options();
        var builder = options.mutate();
        if (sampling.temperature() != null && !settings.capabilities().reasoning()
                && !configured.containsKey("temperature")
                && !Boolean.TRUE.equals(configured.get("maxCompletionTokens")))
            builder.temperature(sampling.temperature());
        if (sampling.reasoningEffort() != null && settings.capabilities().reasoning()
                && (sampling.pinnedReasoning() || !configured.containsKey("reasoningEffort")))
            builder.reasoningEffort(sampling.reasoningEffort().providerValue());
        return builder.build();
    }

    static Prompt withoutTools(Prompt prompt) {
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options))
            throw new IllegalArgumentException("CHAT_UNSUPPORTED_OPTIONS");
        return new Prompt(prompt.getInstructions(), options.mutate().toolCallbacks(List.of()).toolContext(null)
                .toolChoice(null).parallelToolCalls(null).strict(null).build());
    }

    /** Onyx sets {@code tool_choice=REQUIRED} on research cycles; a request without tools is left unchanged. */
    static Prompt requireTools(Prompt prompt) {
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options))
            throw new IllegalArgumentException("CHAT_UNSUPPORTED_OPTIONS");
        if (options.getToolCallbacks() == null || options.getToolCallbacks().isEmpty()) return prompt;
        return new Prompt(prompt.getInstructions(), options.mutate().toolChoice("required").build());
    }

    private static void checkMessage(Message message, boolean tools, boolean vision) {
        if (!tools && (message instanceof ToolResponseMessage
                || message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()))
            throw ChatException.invalid("The selected model does not support tool messages.");
        if (!vision && message instanceof MediaContent content && !content.getMedia().isEmpty())
            throw ChatException.invalid("The selected model does not support media.");
    }

    private static void checkResponse(ChatResponse response, boolean tools, boolean vision) {
        for (var generation : response.getResults()) checkMessage(generation.getOutput(), tools, vision);
    }

    private static int hostedCount(Prompt prompt, TokenCountEstimator tokens) {
        int count = 0;
        for (var message : prompt.getInstructions()) {
            count = Math.addExact(count, tokens.estimate(message.getText()) + 32);
            if (message instanceof MediaContent media)
                count = Math.addExact(count, Math.multiplyExact(media.getMedia().size(), io.memoryos.chat.execution.ChatTurnSetup.IMAGE_INPUT_TOKENS));
            if (message instanceof ToolResponseMessage tool)
                for (var result : tool.getResponses()) count = Math.addExact(count, tokens.estimate(result.responseData()) + 32);
            if (message instanceof AssistantMessage assistant)
                for (var call : assistant.getToolCalls()) count = Math.addExact(count, tokens.estimate(call.name()) + tokens.estimate(call.arguments()) + 32);
        }
        if (prompt.getOptions() instanceof OpenAiChatOptions options && options.getToolCallbacks() != null)
            for (var callback : options.getToolCallbacks()) {
                var tool = callback.getToolDefinition();
                count = Math.addExact(count, tokens.estimate(tool.name()) + tokens.estimate(tool.description())
                        + tokens.estimate(tool.inputSchema()) + 32);
            }
        return count;
    }

}
