package io.memoryos.ai.anthropic;

import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.models.messages.RawMessageStreamEvent;
import io.memoryos.ai.ModelTurns;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * The Messages API as the rest of MemoryOS reads a model: thinking is published to the turn as it streams, a stream
 * closes with its subscription, and finish reasons and usage arrive in the vocabulary the guard and the accounting
 * share with every other provider.
 */
final class AnthropicTurnModel implements ChatModel, ModelTurns {
    private static final int MAX_EVENT_CHARACTERS = 4000;
    private final AnthropicCancellation clients;
    private final Function<AnthropicClientAsync, ChatModel> models;
    private final ChatModel shared;
    private final @Nullable Turn turn;

    AnthropicTurnModel(AnthropicCancellation clients, Function<AnthropicClientAsync, ChatModel> models) {
        this(clients, models, models.apply(clients.async()), null);
    }

    private AnthropicTurnModel(AnthropicCancellation clients, Function<AnthropicClientAsync, ChatModel> models,
                               ChatModel shared, @Nullable Turn turn) {
        this.clients = clients;
        this.models = models;
        this.shared = shared;
        this.turn = turn;
    }

    @Override public ChatModel forTurn(Turn value) { return new AnthropicTurnModel(clients, models, shared, value); }

    /** Anthropic's hosted Web search is not wired; a turn searches with the Web tools MemoryOS adds. */
    @Override public boolean nativeWebSearch() { return false; }

    @Override public ChatResponse call(Prompt prompt) { return normalized(shared.call(prompt)); }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        var active = turn;
        @Nullable Consumer<RawMessageStreamEvent> events = active == null ? null : event -> publish(active, event);
        return clients.stream(events, view -> models.apply(view).stream(prompt))
                .filter(AnthropicTurnModel::carriesContent).map(AnthropicTurnModel::normalized);
    }

    /** Each thinking block starts a paragraph, as the other routes separate the reasoning of each inference. */
    private static void publish(Turn turn, RawMessageStreamEvent event) {
        turn.checkActive().run();
        if (event.contentBlockStart().map(start -> start.contentBlock().isThinking()).orElse(false))
            turn.listener().reasoning("\n\n");
        event.contentBlockDelta().map(delta -> delta.delta()).filter(delta -> delta.isThinking()).ifPresent(delta -> {
            String part = delta.asThinking().thinking();
            for (int offset = 0; offset < part.length(); ) {
                int end = Math.min(part.length(), offset + MAX_EVENT_CHARACTERS);
                if (end < part.length() && Character.isHighSurrogate(part.charAt(end - 1))) end--;
                turn.listener().reasoning(part.substring(offset, end));
                offset = end;
            }
        });
    }

    /** Spring AI marks thinking and signature deltas with chunks that carry no text, tool call or finish reason. */
    private static boolean carriesContent(ChatResponse response) {
        var result = response.getResult();
        if (result == null) return true;
        String text = result.getOutput().getText();
        String reason = result.getMetadata().getFinishReason();
        return text != null && !text.isEmpty() || !result.getOutput().getToolCalls().isEmpty()
                || reason != null && !reason.isBlank();
    }

    /**
     * {@code end_turn} and {@code stop_sequence} read as {@code stop}, {@code max_tokens} as {@code length} and
     * {@code tool_use} as {@code tool_calls}. Anthropic reports cached input beside {@code input_tokens}; the input
     * total here includes it, so the cached part is a subset of the input as it is for every other provider.
     */
    static ChatResponse normalized(ChatResponse response) {
        var metadata = response.getMetadata();
        var usage = metadata.getUsage();
        boolean finished = response.getResults().stream().anyMatch(generation -> {
            String reason = generation.getMetadata().getFinishReason();
            return reason != null && !reason.isBlank();
        });
        if (!finished && usage.getTotalTokens() == 0) return response;
        var generations = response.getResults().stream().map(generation -> new Generation(generation.getOutput(),
                ChatGenerationMetadata.builder().finishReason(finishReason(generation.getMetadata().getFinishReason()))
                        .build())).toList();
        long cacheRead = usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens();
        long cacheWrite = usage.getCacheWriteInputTokens() == null ? 0 : usage.getCacheWriteInputTokens();
        int input = Math.toIntExact(usage.getPromptTokens() + cacheRead + cacheWrite);
        var builder = ChatResponseMetadata.builder().usage(new DefaultUsage(input, usage.getCompletionTokens(),
                input + usage.getCompletionTokens(), usage.getNativeUsage(), usage.getCacheReadInputTokens(),
                usage.getCacheWriteInputTokens()));
        if (metadata.getId() != null) builder.id(metadata.getId());
        if (metadata.getModel() != null) builder.model(metadata.getModel());
        return new ChatResponse(generations, builder.build());
    }

    static @Nullable String finishReason(@Nullable String stopReason) {
        if (stopReason == null) return null;
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> stopReason;
        };
    }
}
