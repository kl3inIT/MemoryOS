package io.memoryos.channel;

import io.memoryos.chat.ChatCommand;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSessionService;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.ChatTurnService;
import io.memoryos.chat.streaming.TextDelta;
import io.memoryos.shared.ActorId;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A question of a channel as a Chat turn, run in this process through the API the web uses: a session held in a
 * channel, the turn at the end of its selected branch, the reply's stream read as it is written, and the saved
 * answer read back for its text and sources.
 */
@Component
class ChannelTurns {
    private static final Duration SAVED_WAIT = Duration.ofMinutes(15);

    private final ChatSessionService sessions;
    private final ChatTurnService turns;

    ChannelTurns(ChatSessionService sessions, ChatTurnService turns) {
        this.sessions = sessions;
        this.turns = turns;
    }

    /** Whether the session is the actor's to continue; another person's, or a deleted one, is not. */
    boolean owns(ActorId actor, UUID session) {
        try {
            sessions.get(actor, session);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Asks in {@code session}, or in a new one when it is null, and returns once the reply has ended.
     *
     * @param request one id a question: a question handed over twice is answered once
     * @param onText each piece of the answer as it is written
     */
    Answer ask(ActorId actor, @Nullable UUID session, String title, UUID request, String text, Consumer<String> onText)
            throws InterruptedException {
        UUID conversation = session != null ? session : sessions.createInChannel(actor, title).id();
        UUID parent = sessions.leaf(actor, conversation);
        var accepted = turns.command(actor, conversation,
                ChatCommand.builder(ChatCommand.Operation.SEND, parent, request, text).build());
        try (var reader = turns.subscribe(actor, conversation, accepted.assistantMessageId(), 0).get()) {
            while (true) {
                var batch = reader.read();
                for (var event : batch.events()) {
                    if (event instanceof TextDelta delta) onText.accept(delta.text());
                }
                // A reset means the buffer cannot be replayed; the saved answer below is the whole of it.
                if (batch.done() || batch.reset() != null) break;
            }
        }
        ChatMessage reply = saved(actor, conversation, accepted.userMessageId(), accepted.assistantMessageId());
        return new Answer(conversation, reply.id(), reply.status(), reply.content() == null ? "" : reply.content(),
                reply.sources(), reply.failureCode());
    }

    /**
     * The reply as saved. It is normally there when its stream ends; a stream that could not be replayed ends early,
     * so a reply still running is waited for, as long as a turn may wait and run.
     */
    private ChatMessage saved(ActorId actor, UUID session, UUID question, UUID reply) throws InterruptedException {
        long deadline = System.nanoTime() + SAVED_WAIT.toNanos();
        while (true) {
            var message = sessions.history(actor, session, question, 1).stream()
                    .filter(candidate -> candidate.id().equals(reply)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("channel reply is missing from its session"));
            if (message.status() != ChatMessage.Status.RUNNING || System.nanoTime() > deadline) return message;
            Thread.sleep(500);
        }
    }

    record Answer(UUID session, UUID assistantMessage, ChatMessage.Status status, String text, List<ChatSource> sources,
                  @Nullable String failureCode) {}
}
