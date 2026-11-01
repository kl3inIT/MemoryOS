package io.memoryos.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.chat.ChatChannel;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatSession;
import io.memoryos.chat.ChatSessionService;
import io.memoryos.chat.ChatTurnService;
import io.memoryos.shared.ActorId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChannelTurnsTest {
    private final ChatSessionService sessions = mock(ChatSessionService.class);
    private final ChatTurnService chat = mock(ChatTurnService.class);
    private final ChannelTurns turns = new ChannelTurns(sessions, chat);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final UUID session = UUID.randomUUID();
    private static final ChatChannel GROUP = new ChatChannel("Zalo", true, false);

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

    @Test
    void aConversationContinuesOnlyWithTheAssistantTheBotAnswersWithNow() {
        UUID hr = UUID.randomUUID();
        UUID bound = UUID.randomUUID();
        UUID moved = UUID.randomUUID();
        var held = new ChatSession(session, hr, UUID.randomUUID(), "Leave", Instant.now(), Instant.now(), null, null, null,
                null, null, false, false, false);
        when(sessions.get(actor, session)).thenReturn(held);
        when(sessions.channelPersona(eq(actor), eq(bound), isNull(), anyBoolean())).thenReturn(hr);
        when(sessions.channelPersona(eq(actor), eq(moved), isNull(), anyBoolean())).thenReturn(UUID.randomUUID());

        assertTrue(turns.continues(actor, session, bound, null, false));
        assertFalse(turns.continues(actor, session, moved, null, false), "a bot moved to another assistant starts over");
        // MEM-298: a conversation held while the bot answered members is not continued once it is public, nor the other
        // way round, whatever the assistant.
        assertFalse(turns.continues(actor, session, bound, null, true), "a bot switched to public starts over");
        when(sessions.get(actor, session)).thenReturn(new ChatSession(session, hr, UUID.randomUUID(), "Giá", Instant.now(),
                Instant.now(), null, null, null, null, null, false, false, true));
        assertTrue(turns.continues(actor, session, bound, null, true));
        assertFalse(turns.continues(actor, session, bound, null, false), "a bot switched back to private starts over");
        when(sessions.get(actor, session)).thenThrow(ChatException.unavailable());
        assertFalse(turns.continues(actor, session, bound, null, false), "another person's or a deleted conversation is not continued");
    }
}
