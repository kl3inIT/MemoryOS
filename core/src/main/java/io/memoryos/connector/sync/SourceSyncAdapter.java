package io.memoryos.connector.sync;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.DueSource;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What one provider contributes to the {@link SourceSyncEngine}: which of its Sources are due, how it walks
 * its content, and how its failures are classified. The engine owns the attempt, its fences, acquisition,
 * removal and how the run ends. One adapter per {@link SourceType}, held by the {@link SourceSyncAdapterRegistry}.
 */
public interface SourceSyncAdapter {
    SourceType type();

    SourceProviderCapabilities capabilities();

    /** Sources whose scheduled run is due, oldest schedule first. */
    List<DueSource> due(int limit);

    /** The credential revision a new run captures; throws a business failure when the credential is unusable. */
    long credentialRevision(TenantId tenant, SourceId source);

    /** Inside a transaction holding the Source lock: whether the credential still serves the Source. */
    boolean credentialCurrent(TenantId tenant, SourceId source, long credentialRevision);

    /**
     * Where a reader opens an acquired item in the provider, or null: built from the provider's file ID or from the
     * address recorded when the item was acquired ({@code sourceUrl}), and only ever an address of this provider.
     * It grants no access; the provider enforces its own permissions when the link is opened.
     */
    @Nullable String documentUrl(@Nullable String providerFileId, @Nullable String sourceUrl);

    /**
     * After the engine moved the Source's next scheduled run one interval ahead because it could not be queued:
     * any other schedule the provider keeps.
     */
    default void postponed(TenantId tenant, SourceId source) {
    }

    /**
     * Inside the transaction that resumed a paused Source: continues what the pause interrupted, when the provider
     * keeps a checkpoint worth continuing.
     */
    default void resumed(TenantId tenant, SourceId source, ActorId actor) {
    }

    /** Inside the transaction that removes one item from a Source: keeps later runs from acquiring it again. */
    default void itemRemoved(TenantId tenant, SourceId source, SourceItemId item) {
    }

    /** Walks one execution slice of the run. */
    Slice walk(SyncRun run);

    /** Classifies a failure that was not isolated to one item. */
    RunFailure classify(RuntimeException failure);

    /** Records that the provider rejected the credential, so the Source asks for it to be reconnected. */
    void authenticationFailed(Work work);

    /**
     * Inside the settling transaction, after the attempt ended without completing: {@code failed} when it
     * failed for good, otherwise it was cancelled or superseded.
     */
    default void ended(Work work, boolean failed, @Nullable String code) {
    }

    /** How a slice ended. */
    enum Slice {
        /** The run has more to do and continues in a later slice. */
        CONTINUE,
        /** The run completed; the adapter called {@link SyncRun#complete()}. */
        COMPLETED,
        /** Item failures crossed the abort threshold. */
        ABORTED,
        /** A fence found the attempt no longer current. */
        STOPPED
    }

    /** A failure that ends the slice, and what the engine does about it. */
    record RunFailure(String code, Kind kind, @Nullable Duration retryAfter) {
        public enum Kind {
            /** The credential must be reconnected; the run fails. */
            RECONNECT,
            /** A transient failure; the attempt is retried after a backoff. */
            RETRY,
            /** The run fails without retrying. */
            FAIL
        }

        public static RunFailure retry(String code, @Nullable Duration retryAfter) {
            return new RunFailure(code, Kind.RETRY, retryAfter);
        }

        public static RunFailure fail(String code) {
            return new RunFailure(code, Kind.FAIL, null);
        }

        public static RunFailure reconnect(String code) {
            return new RunFailure(code, Kind.RECONNECT, null);
        }
    }
}
