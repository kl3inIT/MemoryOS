package io.memoryos.chat.execution;

import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Message;
import com.embabel.chat.SystemMessage;
import com.embabel.chat.UserMessage;
import com.knuddels.jtokkit.api.EncodingType;
import io.memoryos.chat.application.ChatTurnPersistence.TurnContext;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage;
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
        if (TOKENS.estimate(instructions) + TOKENS.estimate(text) + 64 > contextTokenLimit)
            throw ChatException.invalid("The current question exceeds the configured context limit.");
    }

    public static ChatTurnSetup resolve(UUID session, UUID assistant, TurnContext context, int contextTokenLimit,
                                        ChatModelBinding binding) {
        var selected = new ArrayList<Message>();
        int tokens = TOKENS.estimate(context.instructions()) + 32;
        for (var message : context.newestFirst()) {
            int size = TOKENS.estimate(message.content()) + 32;
            if (tokens + size > contextTokenLimit) break;
            tokens += size;
            if (!message.content().isEmpty()) selected.add(message.role() == ChatMessage.Role.USER
                    ? new UserMessage(message.content()) : new AssistantMessage(message.content()));
        }
        if (selected.isEmpty())
            throw ChatException.invalid("The current question exceeds the configured context limit.");
        Collections.reverse(selected);
        if (selected.getFirst() instanceof AssistantMessage) selected.removeFirst();
        selected.addFirst(new SystemMessage(context.instructions()));
        return new ChatTurnSetup(session, assistant, context.actor(), context.tenant(), context.model(), selected, context.deadline(), binding);
    }
}
