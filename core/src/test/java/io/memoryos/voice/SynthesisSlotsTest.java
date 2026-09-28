package io.memoryos.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** A read-aloud slot whose release was lost must come back once it outlives the longest read-aloud. */
class SynthesisSlotsTest {
    private final AtomicLong now = new AtomicLong();
    private final SynthesisSlots slots = new SynthesisSlots(2, Duration.ofMinutes(15), now::get);

    @Test
    void aReleasedSlotIsReturnedOnceAndAFullSetIsBusy() {
        var first = slots.acquire();
        slots.acquire();
        assertEquals("CHAT_CAPACITY_EXCEEDED", assertThrows(VoiceException.class, slots::acquire).code());

        first.release();
        first.release();
        slots.acquire();
        // Releasing twice returned one slot, not two.
        assertEquals("CHAT_CAPACITY_EXCEEDED", assertThrows(VoiceException.class, slots::acquire).code());
    }

    @Test
    void aLeaseHeldPastTheMaximumIsReclaimedAndItsSpeechStopped() {
        var stopped = new AtomicInteger();
        var lost = slots.acquire();
        lost.onReclaim(stopped::incrementAndGet);
        now.set(Duration.ofMinutes(1).toNanos());
        slots.acquire();

        now.set(Duration.ofMinutes(15).toNanos());
        assertEquals("CHAT_CAPACITY_EXCEEDED", assertThrows(VoiceException.class, slots::acquire).code());
        assertEquals(0, stopped.get());

        now.incrementAndGet();
        var replacement = slots.acquire();
        assertEquals(1, stopped.get());
        // The reclaimed lease no longer owns a slot, so its late release frees nothing.
        lost.release();
        assertEquals("CHAT_CAPACITY_EXCEEDED", assertThrows(VoiceException.class, slots::acquire).code());
        replacement.release();
        slots.acquire();
    }
}
