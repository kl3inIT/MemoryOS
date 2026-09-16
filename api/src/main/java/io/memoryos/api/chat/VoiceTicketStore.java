package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.VoiceTicketPurpose;
import io.memoryos.chat.ChatException;
import io.memoryos.iam.identity.ActorId;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Single-use voice WebSocket tickets (Onyx ws-token parity). A ticket lives 60 seconds in this API process and binds
 * the handshake to the member who requested it through a CSRF-protected call and to one voice socket; tickets are never
 * persisted.
 */
@Component
class VoiceTicketStore {
    static final Duration TIME_TO_LIVE = Duration.ofSeconds(60);
    static final int ISSUES_PER_MINUTE = 10;
    private static final int CAPACITY = 10_000;
    private static final int TICKET_LENGTH = 43;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<ActorId, Window> issued = new ConcurrentHashMap<>();
    private final Clock clock;

    VoiceTicketStore() {
        this(Clock.systemUTC());
    }

    VoiceTicketStore(Clock clock) {
        this.clock = clock;
    }

    record Issued(String value, Instant expiresAt) {
        @Override public @NonNull String toString() { return "VoiceTicket[redacted]"; }
    }

    private record Ticket(ActorId actor, VoiceTicketPurpose purpose, Instant expiresAt) {}

    private record Window(Instant start, int count) {}

    Issued issue(ActorId actor, VoiceTicketPurpose purpose) {
        Instant now = clock.instant();
        var window = issued.compute(actor, (ignored, current) -> current == null || !now.isBefore(current.start().plusSeconds(60))
                ? new Window(now, 1) : new Window(current.start(), current.count() + 1));
        if (window.count() > ISSUES_PER_MINUTE) throw ChatException.busy();
        if (tickets.size() >= CAPACITY) {
            tickets.values().removeIf(ticket -> !now.isBefore(ticket.expiresAt()));
            issued.values().removeIf(old -> !now.isBefore(old.start().plusSeconds(60)));
            if (tickets.size() >= CAPACITY) throw ChatException.busy();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var expiresAt = now.plus(TIME_TO_LIVE);
        tickets.put(value, new Ticket(actor, purpose, expiresAt));
        return new Issued(value, expiresAt);
    }

    /** Removes the ticket on every attempt, so a ticket cannot be retried after a failed handshake. */
    boolean consume(@Nullable String value, ActorId actor, VoiceTicketPurpose purpose) {
        if (value == null || value.length() != TICKET_LENGTH) return false;
        var ticket = tickets.remove(value);
        return ticket != null && ticket.actor().equals(actor) && ticket.purpose() == purpose
                && clock.instant().isBefore(ticket.expiresAt());
    }
}
