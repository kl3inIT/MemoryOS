package io.memoryos.shared;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * One pass of a leased background job, the shape every such job in MemoryOS shares: give up on work whose last
 * attempt's lease lapsed, claim the oldest waiting item under a lease, run it outside any transaction, and record a
 * failure against the attempt that failed so the claim's own fencing decides whether it is retried.
 *
 * <p>The steps are the job's own: each decides its transactions, its lease, its attempt limit and what a failure
 * records. This only fixes the order and the two log events, {@code <job>.abandoned} and {@code <job>.failed}, which
 * carry the exception type and never its message, because provider text can reach a message.
 */
public final class LeasedJob {
    private LeasedJob() {}

    /** What a claim has to say for a failure to be logged against it. */
    public interface Claim {
        int attempts();
    }

    /** The steps of one job. */
    public record Steps<C extends Claim>(IntSupplier failAbandoned, Supplier<Optional<C>> claim, Consumer<C> run,
                                         BiConsumer<C, RuntimeException> fail) {}

    /** Runs one pass. Returns whether an item was claimed, so a caller may drain a queue. */
    public static <C extends Claim> boolean runNext(Logger log, String job, Steps<C> steps) {
        int abandoned = steps.failAbandoned().getAsInt();
        if (abandoned > 0)
            log.atWarn().addKeyValue("event", job + ".abandoned").addKeyValue("count", abandoned)
                    .log("Work failed after its last attempt's lease lapsed");
        var claimed = steps.claim().get();
        if (claimed.isEmpty()) return false;
        var claim = claimed.get();
        try {
            steps.run().accept(claim);
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("event", job + ".failed").addKeyValue("attempt", claim.attempts())
                    .addKeyValue("error_type", failure.getClass().getName()).log("Leased work failed");
            steps.fail().accept(claim, failure);
        }
        return true;
    }
}
