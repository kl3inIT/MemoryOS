package io.memoryos.ai.anthropic;

import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAny;
import com.embabel.common.ai.model.LlmOptions;
import io.memoryos.ai.AiException;
import io.memoryos.ai.ModelRequestPolicy;
import io.memoryos.ai.ModelSampling;
import io.memoryos.ai.ModelSettings;
import io.memoryos.ai.ReasoningEffort;
import io.memoryos.ai.TurnFailure;
import java.util.List;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** Native policy applied after Embabel has converted messages, tool callbacks and options. */
final class AnthropicRequestPolicy {
    /** The option that makes a model think within a token budget; without it a level asks for adaptive thinking. */
    static final String THINKING_BUDGET = "thinkingBudget";
    /** The Messages API takes no thinking budget below this. */
    private static final int MIN_THINKING_BUDGET = 1024;

    private AnthropicRequestPolicy() {}

    static ModelRequestPolicy create(ModelSettings settings, TokenCountEstimator tokens) {
        var capabilities = settings.capabilities();
        return ModelRequestPolicy.hosted(tokens, prompt -> {
            var options = options(prompt);
            for (var message : prompt.getInstructions()) checkMessage(message, capabilities.toolCalling(), capabilities.vision());
            Integer limit = settings.maxOutputTokens();
            int output = limit == null ? options.getMaxTokens() : Math.min(options.getMaxTokens(), limit);
            if (output < 1) throw AiException.invalid("Invalid output token limit.");
            var builder = options.mutate().maxTokens(output);
            if (!capabilities.toolCalling()) builder.toolCallbacks(List.of()).toolChoice(null);
            var thinking = options.getThinking();
            if (!capabilities.reasoning()) {
                builder.thinking(null);
                var schema = options.getOutputSchema();
                builder.outputConfig(null);
                if (schema != null) builder.outputSchema(schema);
            } else if (thinking != null && thinking.isEnabled() && thinking.asEnabled().budgetTokens() >= output) {
                // A budget must stay below the output bound; an answer too short to think in does not think.
                if (output > MIN_THINKING_BUDGET) builder.thinkingEnabled(Math.max(MIN_THINKING_BUDGET, output / 2), ThinkingConfigEnabled.Display.SUMMARIZED);
                else builder.thinkingDisabled();
            }
            return new Prompt(prompt.getInstructions(), builder.build());
        });
    }

    /**
     * The level as this model takes it. Off sends thinking disabled; any other level asks for adaptive thinking at
     * that effort, or, for a model configured with a thinking budget, for a budget of that size. Summaries are asked
     * for explicitly, since newer models omit thinking text unless told to show it.
     */
    static AnthropicChatOptions thinking(AnthropicChatOptions options, ReasoningEffort level, ModelSettings settings) {
        var builder = options.mutate();
        if (level == ReasoningEffort.OFF) return builder.thinkingDisabled().build();
        if (Boolean.TRUE.equals(settings.options().get(THINKING_BUDGET)))
            return builder.thinkingEnabled(switch (level) {
                case LOW -> 2048;
                case MEDIUM -> 8192;
                default -> 16384;
            }, ThinkingConfigEnabled.Display.SUMMARIZED).build();
        return builder.thinkingAdaptive(ThinkingConfigAdaptive.Display.SUMMARIZED).effort(switch (level) {
            case LOW -> OutputConfig.Effort.LOW;
            case MEDIUM -> OutputConfig.Effort.MEDIUM;
            default -> OutputConfig.Effort.HIGH;
        }).build();
    }

    static ReasoningEffort level(String configured) {
        return switch (configured) {
            case "none" -> ReasoningEffort.OFF;
            case "low" -> ReasoningEffort.LOW;
            case "medium" -> ReasoningEffort.MEDIUM;
            default -> ReasoningEffort.HIGH;
        };
    }

    /**
     * This turn's creativity and reasoning level, applied after the model configuration. A helper call (Embabel
     * thinking disabled) keeps thinking off; a model that does not reason takes no level, and a reasoning model takes
     * no sampling temperature, which the Messages API refuses beside thinking.
     */
    static ChatOptions withSampling(ChatOptions converted, LlmOptions requested, ModelSampling sampling, ModelSettings settings) {
        if (sampling.isEmpty() || !(converted instanceof AnthropicChatOptions options)) return converted;
        if (requested.getThinking() != null && !requested.getThinking().getEnabled()) return converted;
        if (sampling.temperature() != null && takesTemperature(settings))
            options = options.mutate().temperature(Math.min(1, sampling.temperature())).build();
        if (sampling.reasoningEffort() != null && settings.capabilities().reasoning()
                && (sampling.pinnedReasoning() || !settings.options().containsKey("reasoningEffort")))
            options = thinking(options, sampling.reasoningEffort(), settings);
        return options;
    }

    /** Whether a call may set this model's temperature: not a reasoning model, and not one whose configuration pins it. */
    static boolean takesTemperature(ModelSettings settings) {
        return !settings.capabilities().reasoning() && !settings.options().containsKey("temperature");
    }

    static Prompt withoutTools(Prompt prompt) {
        return new Prompt(prompt.getInstructions(), options(prompt).mutate().toolCallbacks(List.of()).toolChoice(null).build());
    }

    /**
     * A request with tools must call one. The Messages API refuses a forced tool beside thinking, so this inference
     * does not think; a request without tools is left unchanged.
     */
    static Prompt requireTools(Prompt prompt) {
        var options = options(prompt);
        if (options.getToolCallbacks() == null || options.getToolCallbacks().isEmpty()) return prompt;
        var builder = options.mutate().toolChoice(ToolChoice.ofAny(ToolChoiceAny.builder().build()));
        if (options.getThinking() != null) builder.thinkingDisabled();
        return new Prompt(prompt.getInstructions(), builder.build());
    }

    private static AnthropicChatOptions options(Prompt prompt) {
        if (!(prompt.getOptions() instanceof AnthropicChatOptions options)) throw TurnFailure.UNSUPPORTED_OPTIONS.exception();
        return options;
    }

    private static void checkMessage(Message message, boolean tools, boolean vision) {
        if (!tools && (message instanceof ToolResponseMessage
                || message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()))
            throw AiException.invalid("The selected model does not support tool messages.");
        if (!vision && message instanceof MediaContent content && !content.getMedia().isEmpty())
            throw AiException.invalid("The selected model does not support media.");
    }
}
