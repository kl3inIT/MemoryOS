package io.memoryos.connector;

import io.memoryos.shared.ActorId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface SourceRunHistoryService {
    Page list(ActorId actor, SourceId source, Query query);
    SourceRun get(ActorId actor, SourceId source, UUID runId);
    ErrorPage errors(ActorId actor, SourceId source, UUID runId, @Nullable String cursor, int size);

    /** An empty {@code statuses} set lists runs in every status. */
    record Query(@Nullable String cursor, int size, Set<SourceRunStatus> statuses,
            @Nullable SourceRunTrigger trigger, @Nullable Instant from, @Nullable Instant to) {
        public Query {
            statuses = Set.copyOf(statuses);
        }
    }
    record Page(List<SourceRun> items, @Nullable String nextCursor, @Nullable SourceRun current,
            @Nullable SourceRun lastCompleted, @Nullable SourceRun lastSuccessful, long totalItems) {}
    record ErrorPage(List<SourceRunError> items, @Nullable String nextCursor) {}
}
