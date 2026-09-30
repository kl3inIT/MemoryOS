package io.memoryos.ai.openai;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.ObjectMapper;

/**
 * Messages to Responses input items, and a response's output items back onto the assistant message that continues
 * the tool loop, after Spring AI's {@code ResponsesItemMapper}. Requests are stateless, so the exact prior output
 * (reasoning, hosted search, function calls) rides in an assistant message property and is replayed as it was.
 */
@NullMarked
final class ResponsesInputMapper {
    /** The assistant message property holding the output items of the response that asked for tool calls. */
    static final String OUTPUT_ITEMS = "memoryos.openai.responses.output";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ResponsesInputMapper() {
    }

    static List<Map<String, Object>> input(Message message) {
        var items = new ArrayList<Map<String, Object>>();
        switch (message) {
            case SystemMessage system -> items.add(Map.of("type", "message", "role", "system", "content", text(system.getText())));
            case UserMessage user -> {
                var content = new ArrayList<Map<String, Object>>();
                content.add(Map.of("type", "input_text", "text", text(user.getText())));
                for (var media : user.getMedia())
                    content.add(Map.of("type", "input_image", "detail", "auto", "image_url",
                            "data:" + media.getMimeType() + ";base64," + Base64.getEncoder().encodeToString(media.getDataAsByteArray())));
                items.add(Map.of("type", "message", "role", "user", "content", content));
            }
            case AssistantMessage assistant -> {
                Object echoed = assistant.getMetadata().get(OUTPUT_ITEMS);
                if (echoed instanceof String json) {
                    // Exact prior output (reasoning, hosted search, function calls) preserves continuation.
                    for (Object item : JSON.readValue(json, List.class)) {
                        @SuppressWarnings("unchecked") var map = (Map<String, Object>) item;
                        items.add(map);
                    }
                } else {
                    if (assistant.getText() != null && !assistant.getText().isEmpty())
                        items.add(Map.of("type", "message", "role", "assistant", "content", assistant.getText()));
                    for (var call : assistant.getToolCalls())
                        items.add(Map.of("type", "function_call", "call_id", call.id(), "name", call.name(), "arguments", call.arguments()));
                }
            }
            case ToolResponseMessage tool -> {
                for (var response : tool.getResponses())
                    items.add(Map.of("type", "function_call_output", "call_id", response.id(), "output", text(response.responseData())));
            }
            default -> throw new IllegalArgumentException("Unsupported message type");
        }
        return items;
    }

    /** The output items as {@link #input} replays them on the next request. */
    static String echo(List<?> outputItems) {
        return JSON.writeValueAsString(outputItems);
    }

    private static String text(@Nullable String value) {
        return value == null ? "" : value;
    }
}
