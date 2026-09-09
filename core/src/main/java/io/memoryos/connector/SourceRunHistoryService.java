package io.memoryos.connector;

import io.memoryos.iam.ActorId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface SourceRunHistoryService {
    Page list(ActorId actor, SourceId source, Query query);
    SourceRun get(ActorId actor, SourceId source, UUID runId);
    ErrorPage errors(ActorId actor, SourceId source, UUID runId, @Nullable String cursor, int size);

    record Query(@Nullable String cursor, int size, @Nullable SourceRunStatus status,
            @Nullable SourceRunTrigger trigger, @Nullable Instant from, @Nullable Instant to) {}
    record Page(List<SourceRun> items, @Nullable String nextCursor, @Nullable SourceRun current,
            @Nullable SourceRun lastCompleted, @Nullable SourceRun lastSuccessful) {}
    record ErrorPage(List<SourceRunError> items, @Nullable String nextCursor) {}
}
