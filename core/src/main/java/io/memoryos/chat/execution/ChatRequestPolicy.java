package io.memoryos.chat.execution;

import io.memoryos.chat.ChatException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** Immutable binding policy shared by admission, history selection and every native request. */
public record ChatRequestPolicy(TokenCountEstimator tokens, ToIntFunction<Prompt> framing,
                                UnaryOperator<Prompt> options, Consumer<ChatResponse> response) {
    public ChatRequestPolicy {
        Objects.requireNonNull(tokens);
        Objects.requireNonNull(framing);
        Objects.requireNonNull(options);
        Objects.requireNonNull(response);
    }

    /** The hosted contract is a conservative estimate, not an assertion about a server template. */
    public static ChatRequestPolicy hosted(TokenCountEstimator tokens, UnaryOperator<Prompt> options) {
        return new ChatRequestPolicy(tokens, prompt -> {
            int count = 0;
            for (var message : prompt.getInstructions()) {
                count = Math.addExact(count, tokens.estimate(message.getText()) + 32);
                if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage tool)
                    for (var result : tool.getResponses()) count = Math.addExact(count, tokens.estimate(result.responseData()) + 32);
                if (message instanceof org.springframework.ai.chat.messages.AssistantMessage assistant)
                    for (var call : assistant.getToolCalls()) count = Math.addExact(count, tokens.estimate(call.name()) + tokens.estimate(call.arguments()) + 32);
            }
            if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions toolOptions
                    && toolOptions.getToolCallbacks() != null)
                for (var callback : toolOptions.getToolCallbacks()) {
                    var tool = callback.getToolDefinition();
                    count = Math.addExact(count, tokens.estimate(tool.name()) + tokens.estimate(tool.description())
                            + tokens.estimate(tool.inputSchema()) + 32);
                }
            return count;
        }, options, ignored -> {});
    }

    public void validateQuestion(String instructions, String text, int inputBudget) {
        requireFits(List.of(new SystemMessage(instructions), new UserMessage(text)), inputBudget);
    }

    public void requireFits(List<Message> messages, int inputBudget) {
        if (framing.applyAsInt(new Prompt(messages)) > inputBudget)
            throw ChatException.invalid("The current prompt exceeds the configured context limit.");
    }

    public int inputTokens(Prompt prompt, int inputBudget) {
        int count = framing.applyAsInt(prompt);
        if (count > inputBudget)
            throw new IllegalStateException("CHAT_CONTEXT_LIMIT");
        return count;
    }

    public Prompt request(Prompt prompt, int inputBudget) {
        var effective = options.apply(prompt);
        inputTokens(effective, inputBudget);
        return effective;
    }
}
