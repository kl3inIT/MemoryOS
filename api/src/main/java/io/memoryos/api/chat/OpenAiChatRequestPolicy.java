package io.memoryos.api.chat;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ModelSettings;
import io.memoryos.chat.execution.ChatRequestPolicy;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** Native policy applied after Embabel has converted messages, tool callbacks and options. */
final class OpenAiChatRequestPolicy {
    private OpenAiChatRequestPolicy() {}

    static ChatRequestPolicy create(ModelSettings settings, TokenCountEstimator tokens) {
        boolean smol = ChatTokenizerProfiles.SMOL.equals(settings.tokenizerProfile());
        var capabilities = settings.capabilities();
        return new ChatRequestPolicy(tokens,
                prompt -> smol ? tokens.estimate(renderSmol(prompt.getInstructions())) : hostedCount(prompt, tokens),
                prompt -> {
                    if (!(prompt.getOptions() instanceof OpenAiChatOptions options))
                        throw new IllegalArgumentException("CHAT_UNSUPPORTED_OPTIONS");
                    for (var message : prompt.getInstructions()) checkMessage(message, capabilities.toolCalling(), capabilities.vision());
                    boolean completionTokens = Boolean.TRUE.equals(settings.options().get("maxCompletionTokens"));
                    Integer requested = completionTokens ? options.getMaxCompletionTokens() : options.getMaxTokens();
                    int output = requested == null ? settings.maxOutputTokens() : Math.min(requested, settings.maxOutputTokens());
                    if (output < 1) throw ChatException.invalid("Invalid output token limit.");
                    var builder = options.mutate().maxCompletionTokens(null).maxTokens(null);
                    if (completionTokens) builder.maxCompletionTokens(output);
                    else builder.maxTokens(output);
                    if (!capabilities.toolCalling()) builder.toolCallbacks(List.of()).toolContext(null).toolChoice(null).parallelToolCalls(null).strict(null);
                    if (!capabilities.reasoning()) builder.reasoningEffort(null);
                    if (smol) {
                        if (options.getOutputAudio() != null || options.getOutputModalities() != null || options.getExtraBody() != null)
                            throw ChatException.invalid("Unsupported text-only model options.");
                        builder.n(1);
                    }
                    return new Prompt(prompt.getInstructions(), builder.build());
                }, response -> checkResponse(response, capabilities.toolCalling(), capabilities.vision()));
    }

    static Prompt withoutTools(Prompt prompt) {
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options))
            throw new IllegalArgumentException("CHAT_UNSUPPORTED_OPTIONS");
        return new Prompt(prompt.getInstructions(), options.mutate().toolCallbacks(List.of()).toolContext(null)
                .toolChoice(null).parallelToolCalls(null).strict(null).build());
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

    /** Exact text-only expansion of the pinned 368-byte template, not a generic Jinja interpreter. */
    static String renderSmol(List<Message> messages) {
        var rendered = new StringBuilder();
        if (!messages.isEmpty() && messages.getFirst().getMessageType() != MessageType.SYSTEM)
            rendered.append("<|im_start|>system\nYou are a helpful AI assistant named SmolLM, trained by Hugging Face<|im_end|>\n");
        for (var message : messages) {
            checkMessage(message, false, false);
            if (message.getMessageType() != MessageType.SYSTEM && message.getMessageType() != MessageType.USER
                    && message.getMessageType() != MessageType.ASSISTANT)
                throw ChatException.invalid("Unsupported message role for the installed tokenizer profile.");
            rendered.append("<|im_start|>").append(message.getMessageType().getValue()).append('\n')
                    .append(message.getText() == null ? "" : message.getText()).append("<|im_end|>\n");
        }
        return rendered.append("<|im_start|>assistant\n").toString();
    }
}
