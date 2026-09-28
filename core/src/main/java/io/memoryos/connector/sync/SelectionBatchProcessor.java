package io.memoryos.connector.sync;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceSelectionProcessor;
import io.memoryos.connector.sync.persistence.SelectionOperations;
import io.memoryos.shared.TenantId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies an accepted selection in bounded batches, whatever the provider. A batch hands the request back
 * once it has run for {@link #BATCH_MILLIS} or made its share of provider requests, so one claim never holds a
 * worker; a business failure ends the request, superseded when newer authority replaced it.
 */
public abstract class SelectionBatchProcessor implements SourceSelectionProcessor {
    /** A batch hands the work back when it has run this long. */
    protected static final long BATCH_MILLIS = 30_000;

    protected final TransactionTemplate transactions;
    private final SelectionOperations operations;

    protected SelectionBatchProcessor(SelectionOperations operations, PlatformTransactionManager transactionManager) {
        this.operations = operations;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public final Optional<Work> claim(TenantId tenant, SourceOperationId operation, UUID delivery) {
        return Objects.requireNonNull(transactions.execute(_ -> operations.claim(tenant, operation, delivery)));
    }

    @Override
    public final boolean renew(Work work) {
        return Boolean.TRUE.equals(transactions.execute(_ -> operations.renew(work)));
    }

    @Override
    public final Result execute(Work work) {
        long started = System.nanoTime();
        try {
            return verify(work, started);
        } catch (ContinueBatch exception) {
            return continueLater(work, started, null);
        } catch (BusinessException exception) {
            boolean stale = exception.category() != FailureCategory.VALIDATION;
            return finish(work, stale ? Result.SUPERSEDED : Result.FAILED, exception.code());
        }
    }

    /** Verifies the next batch and, once every entry is verified, activates the selection. */
    protected abstract Result verify(Work work, long started);

    /** Hands the request back: at once after a full batch, or after a backoff when {@code code} failed it. */
    protected final Result continueLater(Work work, long started, @Nullable String code) {
        transactions.executeWithoutResult(_ -> operations.continueLater(work, elapsed(started), code));
        return Result.CONTINUED;
    }

    protected final Result finish(Work work, Result result, @Nullable String code) {
        transactions.executeWithoutResult(_ -> operations.finish(work, result.name(), code));
        return result;
    }

    /** Ends the batch once it has run for its share of time. */
    protected static void requireBatchTime(long started) {
        if (elapsed(started) >= BATCH_MILLIS) throw new ContinueBatch();
    }

    protected static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    /** Signals that the batch is spent and the rest continues under a fresh claim. */
    protected static final class ContinueBatch extends RuntimeException {
        public ContinueBatch() {
            super(null, null, false, false);
        }
    }
}
