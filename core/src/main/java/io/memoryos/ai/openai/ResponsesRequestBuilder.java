package io.memoryos.ai.openai;

import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

/**
 * A prompt and its options as a stateless Responses request ({@code store=false}), after Spring AI's
 * {@code ResponsesRequestBuilder}: input items, sampling, reasoning effort and summaries, function tools, hosted
 * {@code web_search} and the tool choice.
 */
@NullMarked
final class ResponsesRequestBuilder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final boolean reasoning;
    private final boolean summaries;

    /** {@code summaries} asks for reasoning summaries; it has no effect on a model that does not reason. */
    ResponsesRequestBuilder(boolean reasoning, boolean summaries) {
        this.reasoning = reasoning;
        this.summaries = summaries && reasoning;
    }

    boolean summaries() {
        return summaries;
    }

    /** {@code tools} is false on the final cycle, which removes every tool; hosted search is a tool as well. */
    ResponseCreateParams build(Prompt prompt, OpenAiChatOptions options, boolean tools, boolean web) {
        var mapper = ObjectMappers.jsonMapper();
        var input = new ArrayList<ResponseInputItem>();
        for (var message : prompt.getInstructions())
            for (var item : ResponsesInputMapper.input(message)) input.add(mapper.convertValue(item, ResponseInputItem.class));
        var builder = ResponseCreateParams.builder().model(options.getModel()).store(false)
                .input(ResponseCreateParams.Input.ofResponse(input));
        Integer maxOutput = options.getMaxCompletionTokens() != null ? options.getMaxCompletionTokens() : options.getMaxTokens();
        if (maxOutput != null) builder.maxOutputTokens(maxOutput.longValue());
        if (options.getTemperature() != null) builder.temperature(options.getTemperature());
        if (options.getTopP() != null) builder.topP(options.getTopP());
        var reasoningOptions = new LinkedHashMap<String, Object>();
        // Onyx ReasoningEffort.AUTO is "medium" for OpenAI (onyx/llm/models.py). Omitting it lets GPT-5.1 and later
        // default to no reasoning, so a reasoning model neither thought nor streamed a summary while it worked.
        String effort = options.getReasoningEffort() != null ? options.getReasoningEffort() : reasoning ? "medium" : null;
        if (effort != null) reasoningOptions.put("effort", effort);
        // As Onyx, summaries accompany every reasoning request, so the stream carries packets while the model thinks.
        if (summaries && !"none".equals(effort)) reasoningOptions.put("summary", "auto");
        if (!reasoningOptions.isEmpty()) builder.putAdditionalBodyProperty("reasoning", JsonValue.from(reasoningOptions));
        if (reasoning) builder.include(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT));
        if (tools) {
            var declared = new ArrayList<Tool>();
            var callbacks = options.getToolCallbacks();
            for (var callback : callbacks == null ? List.<ToolCallback>of() : callbacks) {
                var definition = callback.getToolDefinition();
                var function = new LinkedHashMap<String, Object>();
                function.put("type", "function");
                function.put("name", definition.name());
                function.put("description", definition.description());
                function.put("parameters", JSON.readValue(definition.inputSchema(), Map.class));
                function.put("strict", false);
                declared.add(mapper.convertValue(function, Tool.class));
            }
            if (web) declared.add(mapper.convertValue(Map.of("type", "web_search"), Tool.class));
            builder.tools(declared);
            Object choice = toolChoice(options.getToolChoice());
            if (choice != null) builder.putAdditionalBodyProperty("tool_choice", JsonValue.from(choice));
            if (options.getParallelToolCalls() != null) builder.parallelToolCalls(options.getParallelToolCalls());
        }
        return builder.build();
    }

    /**
     * A Chat Completions tool choice as Responses expects it: {@code auto}, {@code none} and {@code required} are the
     * same strings; a named function moves its name from {@code function.name} to the top level.
     */
    static @Nullable Object toolChoice(@Nullable Object choice) {
        if (choice == null) return null;
        Object value = choice instanceof String text && text.trim().startsWith("{") ? JSON.readValue(text, Map.class) : choice;
        if (value instanceof String text) {
            if (!Set.of("auto", "none", "required").contains(text)) throw new IllegalArgumentException("Unsupported tool choice");
            return text;
        }
        Map<?, ?> map = value instanceof Map<?, ?> direct ? direct : JSON.convertValue(value, Map.class);
        if (!"function".equals(map.get("type"))) throw new IllegalArgumentException("Unsupported tool choice");
        if (map.get("function") instanceof Map<?, ?> named && named.get("name") instanceof String name) return Map.of("type", "function", "name", name);
        if (map.get("name") instanceof String name) return Map.of("type", "function", "name", name);
        throw new IllegalArgumentException("Unsupported tool choice");
    }
}
