package io.memoryos.voice;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The read-aloud provider slots, held as leases. Every stream returns its lease when it ends; a lease that outlives the
 * longest a read-aloud may run was lost, so the next acquisition reclaims it and stops the speech it belonged to. A lost
 * release therefore costs one slot for at most that long instead of locking read-aloud for everyone.
 */
@NullMarked
final class SynthesisSlots {
    private static final Logger LOG = LoggerFactory.getLogger(SynthesisSlots.class);
    private final int capacity;
    private final long maxHoldNanos;
    private final LongSupplier nanos;
    private final Set<Lease> leases = new LinkedHashSet<>();

    SynthesisSlots(int capacity, Duration maxHold, LongSupplier nanos) {
        if (capacity < 1 || maxHold.isNegative() || maxHold.isZero())
            throw new IllegalArgumentException("Read-aloud slots need a capacity and a positive maximum hold");
        this.capacity = capacity;
        this.maxHoldNanos = maxHold.toNanos();
        this.nanos = nanos;
    }

    /** Takes a slot, first reclaiming leases held past the maximum; refuses as busy when every slot is still held. */
    Lease acquire() {
        List<Lease> reclaimed = new ArrayList<>();
        Lease lease;
        synchronized (this) {
            long now = nanos.getAsLong();
            for (var iterator = leases.iterator(); iterator.hasNext(); ) {
                var held = iterator.next();
                if (now - held.started > maxHoldNanos) {
                    iterator.remove();
                    reclaimed.add(held);
                }
            }
            lease = leases.size() < capacity ? new Lease(now) : null;
            if (lease != null) leases.add(lease);
        }
        // Stopping an abandoned speech may touch the network, so it runs outside the lock.
        for (var held : reclaimed) {
            LOG.atWarn().addKeyValue("event", "voice.synthesis.slot_reclaimed")
                    .log("A read-aloud slot outlived its maximum hold and was reclaimed");
            held.reclaim();
        }
        if (lease == null) throw VoiceException.busy();
        return lease;
    }

    /** One held slot. Releasing is idempotent, and releasing a reclaimed lease does nothing. */
    final class Lease {
        private final long started;
        private @Nullable Runnable onReclaim;

        private Lease(long started) {
            this.started = started;
        }

        void release() {
            synchronized (SynthesisSlots.this) {
                leases.remove(this);
            }
        }

        /** What stops the speech holding this slot if the lease is reclaimed. */
        void onReclaim(Runnable stop) {
            synchronized (SynthesisSlots.this) {
                onReclaim = stop;
            }
        }

        private void reclaim() {
            Runnable stop;
            synchronized (SynthesisSlots.this) {
                stop = onReclaim;
            }
            if (stop == null) return;
            try {
                stop.run();
            } catch (RuntimeException failure) {
                LOG.atWarn().addKeyValue("event", "voice.synthesis.reclaimed_stop_failed")
                        .addKeyValue("error_type", failure.getClass().getName()).log("A reclaimed read-aloud did not stop");
            }
        }
    }
}
