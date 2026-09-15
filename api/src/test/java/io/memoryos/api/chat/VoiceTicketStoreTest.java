package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.chat.ChatException;
import io.memoryos.iam.identity.ActorId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoiceTicketStoreTest {
    private final MutableClock clock = new MutableClock();
    private final VoiceTicketStore tickets = new VoiceTicketStore(clock);
    private final ActorId actor = new ActorId(UUID.randomUUID());

    @Test
    void ticketIsSingleUseAndBoundToTheRequestingMember() {
        var issued = tickets.issue(actor);
        assertEquals(clock.instant().plus(VoiceTicketStore.TIME_TO_LIVE), issued.expiresAt());
        assertFalse(tickets.consume(issued.value(), new ActorId(UUID.randomUUID())));
        assertFalse(tickets.consume(issued.value(), actor), "a failed attempt still spends the ticket");
        var second = tickets.issue(actor);
        assertTrue(tickets.consume(second.value(), actor));
        assertFalse(tickets.consume(second.value(), actor));
        assertFalse(tickets.consume(null, actor));
    }

    @Test
    void expiredTicketIsRejected() {
        var issued = tickets.issue(actor);
        clock.advance(VoiceTicketStore.TIME_TO_LIVE);
        assertFalse(tickets.consume(issued.value(), actor));
    }

    @Test
    void issuingIsLimitedPerMemberInAFixedMinuteWindow() {
        for (int i = 0; i < VoiceTicketStore.ISSUES_PER_MINUTE; i++) tickets.issue(actor);
        assertEquals("CHAT_CAPACITY_EXCEEDED", assertThrows(ChatException.class, () -> tickets.issue(actor)).code());
        tickets.issue(new ActorId(UUID.randomUUID()));
        clock.advance(Duration.ofSeconds(60));
        tickets.issue(actor);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-15T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
