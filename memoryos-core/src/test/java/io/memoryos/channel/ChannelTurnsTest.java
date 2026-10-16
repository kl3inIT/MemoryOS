package io.memoryos.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.chat.ChatChannel;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatSessionService;
import io.memoryos.chat.ChatTurnService;
import io.memoryos.shared.ActorId;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChannelTurnsTest {
    private final ChatSessionService sessions = mock(ChatSessionService.class);
    private final ChatTurnService chat = mock(ChatTurnService.class);
    private final ChannelTurns turns = new ChannelTurns(sessions, chat);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final UUID session = UUID.randomUUID();
    private static final ChatChannel GROUP = new ChatChannel("Zalo", true);

    @Test
    void aQuestionWaitsForTheTurnItsConversationIsRunningAndAsksAtTheNewLeaf() throws Exception {
        UUID before = UUID.randomUUID();
        UUID after = UUID.randomUUID();
        when(sessions.leaf(actor, session)).thenReturn(before, before, after);
        var accepted = new ChatTurnService.Accepted(UUID.randomUUID(), UUID.randomUUID(), null, null);
        when(chat.command(eq(actor), eq(session), any())).thenThrow(ChatException.conflict(), ChatException.conflict())
                .thenReturn(accepted);
        var waits = new AtomicInteger();

        assertEquals(accepted, turns.accept(actor, session, UUID.randomUUID(), "Question", GROUP, Duration.ofSeconds(30),
                waits::incrementAndGet));
        assertEquals(1, waits.get(), "the sender is told once that the question waits");
        verify(chat, times(3)).command(eq(actor), eq(session), argThat(command -> GROUP.equals(command.channel())));
    }

    @Test
    void aQuestionStopsWaitingAtItsBoundAndAnotherRefusalIsNotWaitedFor() {
        when(sessions.leaf(actor, session)).thenReturn(UUID.randomUUID());
        when(chat.command(eq(actor), eq(session), any())).thenThrow(ChatException.conflict());
        assertEquals("CHAT_CONFLICT", assertThrows(ChatException.class, () ->
                turns.accept(actor, session, UUID.randomUUID(), "Question", GROUP, Duration.ZERO, () -> {})).code());

        when(chat.command(eq(actor), eq(session), any())).thenThrow(ChatException.busy());
        var waits = new AtomicInteger();
        assertEquals("CHAT_CAPACITY_EXCEEDED", assertThrows(ChatException.class, () ->
                turns.accept(actor, session, UUID.randomUUID(), "Question", GROUP, Duration.ofSeconds(30), waits::incrementAndGet)).code());
        assertEquals(0, waits.get());
    }
}
