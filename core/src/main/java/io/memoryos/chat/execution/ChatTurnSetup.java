package io.memoryos.chat.execution;

import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Message;
import com.embabel.chat.SystemMessage;
import com.embabel.chat.UserMessage;
import com.knuddels.jtokkit.api.EncodingType;
import io.memoryos.chat.application.ChatTurnPersistence.TurnContext;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.prompts.ChatPrompts;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * Resolved once, held only for the lifetime of this execution.
 */
public record ChatTurnSetup(UUID sessionId, UUID assistantMessageId, ActorId actor, TenantId tenant,
                            String model, List<Message> messages, Instant deadline, ChatModelBinding binding) {
    public ChatTurnSetup {
        messages = List.copyOf(messages);
        Objects.requireNonNull(binding);
    }

    private static final TokenCountEstimator TOKENS = new JTokkitTokenCountEstimator(EncodingType.O200K_BASE);

    public static void validateQuestion(String instructions, String text, int contextTokenLimit) {
        validateQuestion(instructions, text, contextTokenLimit, TOKENS);
    }

    public static void validateQuestion(String instructions, String text, int contextTokenLimit, TokenCountEstimator tokens) {
        if (tokens.estimate(instructions) + tokens.estimate(text) + 64 > contextTokenLimit)
            throw ChatException.invalid("The current question exceeds the configured context limit.");
    }

    public static void validateQuestion(String instructions, String text, int contextTokenLimit, ChatModelBinding binding) {
        validateQuestion(instructions(instructions, binding), text, historyLimit(contextTokenLimit, binding), binding.tokens());
    }

    private static String instructions(String instructions, ChatModelBinding binding) {
        return ChatPrompts.resolve(instructions, binding.toolCalling(), Instant.now());
    }

    private static int historyLimit(int limit, ChatModelBinding binding) {
        return binding.toolCalling() ? limit - Math.min(4096, limit / 3) : limit;
    }

    public static ChatTurnSetup resolve(UUID session, UUID assistant, TurnContext context, int contextTokenLimit,
                                        ChatModelBinding binding) {
        var selected = new ArrayList<Message>();
        String instructions = instructions(context.instructions(), binding);
        // Reserve room for tool schemas/results; transcript is still stored in full.
        int historyLimit = historyLimit(contextTokenLimit, binding);
        int tokens = binding.tokens().estimate(instructions) + 32;
        for (var message : context.newestFirst()) {
            if (message.content() == null || message.content().isEmpty()) continue;
            int size = binding.tokens().estimate(message.content()) + 32;
            if (tokens + size > historyLimit) break;
            tokens += size;
            selected.add(message.role() == ChatMessage.Role.USER
                    ? new UserMessage(message.content()) : new AssistantMessage(message.content()));
        }
        if (selected.isEmpty())
            throw ChatException.invalid("The current question exceeds the configured context limit.");
        Collections.reverse(selected);
        if (selected.getFirst() instanceof AssistantMessage) selected.removeFirst();
        selected.addFirst(new SystemMessage(instructions));
        return new ChatTurnSetup(session, assistant, context.actor(), context.tenant(), binding.service().getName(), selected, context.deadline(), binding);
    }
}
