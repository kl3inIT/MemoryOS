package io.memoryos.chat.execution;

import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Message;
import com.embabel.chat.SystemMessage;
import com.embabel.chat.UserMessage;
import com.knuddels.jtokkit.api.EncodingType;
import io.memoryos.chat.application.ChatTurnPersistence.TurnContext;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatTurnOptions;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

/**
 * Resolved once, held only for the lifetime of this execution.
 */
public record ChatTurnSetup(UUID sessionId, UUID assistantMessageId, ActorId actor, TenantId tenant,
                            String model, List<Message> messages, Instant deadline, ChatModelBinding binding, ChatTurnOptions options) {
    public ChatTurnSetup(UUID sessionId, UUID assistantMessageId, ActorId actor, TenantId tenant,
                         String model, List<Message> messages, Instant deadline, ChatModelBinding binding) {
        this(sessionId, assistantMessageId, actor, tenant, model, messages, deadline, binding, ChatTurnOptions.DEFAULT);
    }
    public ChatTurnSetup {
        messages = List.copyOf(messages);
        Objects.requireNonNull(binding);
    }

    private static final class Hosted {
        private static final ChatRequestPolicy POLICY = ChatRequestPolicy.hosted(
                new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), prompt -> prompt);
    }

    public static void validateQuestion(String instructions, String text, int contextTokenLimit) {
        Hosted.POLICY.validateQuestion(instructions, text, contextTokenLimit);
    }

    /** Matches Embabel's consolidation, with the unpredictable date frozen before reservation. */
    public static String instructions(String instructions, String contribution) {
        return contribution.isEmpty() ? instructions : instructions.isEmpty() ? contribution : contribution + "\n\n" + instructions;
    }

    public static void validateQuestion(String instructions, String text, int contextTokenLimit, ChatModelBinding binding,
                                        String contribution) {
        binding.policy().validateQuestion(instructions(instructions, contribution), text, historyLimit(contextTokenLimit, binding));
    }

    private static int historyLimit(int limit, ChatModelBinding binding) {
        return binding.toolCalling() ? limit - Math.min(4096, limit / 3) : limit;
    }

    public static ChatTurnSetup resolve(UUID session, UUID assistant, TurnContext context, int contextTokenLimit,
                                        ChatModelBinding binding, String contribution) {
        binding = binding.forOptions(context.options());
        if (context.options().contextTokenLimit() != null) contextTokenLimit = Math.min(contextTokenLimit, context.options().contextTokenLimit());
        var selected = new ArrayList<Message>();
        var nativeMessages = new ArrayList<org.springframework.ai.chat.messages.Message>();
        String instructions = instructions(context.instructions(), contribution);
        nativeMessages.add(new org.springframework.ai.chat.messages.SystemMessage(instructions));
        int historyLimit = historyLimit(contextTokenLimit, binding);
        for (var message : context.newestFirst()) {
            if (message.content() == null || message.content().isEmpty()) continue;
            var nativeMessage = message.role() == ChatMessage.Role.USER
                    ? new org.springframework.ai.chat.messages.UserMessage(message.content())
                    : new org.springframework.ai.chat.messages.AssistantMessage(message.content());
            nativeMessages.add(1, nativeMessage);
            if (binding.policy().framing().applyAsInt(new org.springframework.ai.chat.prompt.Prompt(nativeMessages)) > historyLimit) {
                nativeMessages.remove(1);
                break;
            }
            selected.add(message.role() == ChatMessage.Role.USER
                    ? new UserMessage(message.content()) : new AssistantMessage(message.content()));
        }
        if (selected.isEmpty())
            throw ChatException.invalid("The current question exceeds the configured context limit.");
        Collections.reverse(selected);
        if (selected.getFirst() instanceof AssistantMessage) selected.removeFirst();
        selected.addFirst(new SystemMessage(instructions));
        return new ChatTurnSetup(session, assistant, context.actor(), context.tenant(), binding.service().getName(), selected, context.deadline(), binding, context.options());
    }
}
