package io.memoryos.channel;

import io.memoryos.chat.ChatChannel;
import io.memoryos.chat.ChatCommand;
import io.memoryos.chat.ChatException;
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
import java.util.function.Supplier;
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
    private static final Duration RETRY = Duration.ofSeconds(1);
    private static final String CONFLICT = "CHAT_CONFLICT";

    private final ChatSessionService sessions;
    private final ChatTurnService turns;

    ChannelTurns(ChatSessionService sessions, ChatTurnService turns) {
        this.sessions = sessions;
        this.turns = turns;
    }

    /**
     * Whether the session is the actor's to continue: another person's, or a deleted one, is not, and neither is one
     * held with another assistant than the bot answers with now, so moving a bot starts its conversations over.
     */
    boolean continues(ActorId actor, UUID session, UUID connection) {
        try {
            return sessions.get(actor, session).personaId().equals(sessions.channelPersona(actor, connection));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Asks in {@code session}, or in a new one when it is null, and returns once the reply has ended. A conversation
     * runs one turn at a time: while it runs another, the question waits for it, up to {@code wait}, and
     * {@code onWaiting} is told once.
     *
     * @param request one id a question: a question handed over twice is answered once
     * @param channel where it was asked, which the answer model is told
     * @param connection the bot it was asked through, whose assistant a new session is held with
     * @param onAccepted called once the question is accepted; it opens the reply and returns where the answer's
     *        text goes as it is written, while the turn is already being prepared
     */
    Answer ask(ActorId actor, @Nullable UUID session, String title, UUID request, String text, ChatChannel channel,
               UUID connection, Duration wait, Runnable onWaiting, Supplier<Consumer<String>> onAccepted)
            throws InterruptedException {
        UUID conversation = session != null ? session : sessions.createInChannel(actor, title, connection).id();
        var accepted = accept(actor, conversation, request, text, channel, wait, onWaiting);
        Consumer<String> onText = onAccepted.get();
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

    ChatTurnService.Accepted accept(ActorId actor, UUID conversation, UUID request, String text, ChatChannel channel,
                                    Duration wait, Runnable onWaiting) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        boolean waiting = false;
        while (true) {
            try {
                UUID parent = sessions.leaf(actor, conversation);
                return turns.command(actor, conversation,
                        ChatCommand.builder(ChatCommand.Operation.SEND, parent, request, text).channel(channel).build());
            } catch (ChatException conflict) {
                // The conversation has a reply running, or moved on while this was read: wait, then ask at its leaf.
                if (!CONFLICT.equals(conflict.code()) || System.nanoTime() > deadline) throw conflict;
                if (!waiting) {
                    waiting = true;
                    onWaiting.run();
                }
                Thread.sleep(RETRY.toMillis());
            }
        }
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
