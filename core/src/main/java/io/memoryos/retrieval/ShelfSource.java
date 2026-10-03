package io.memoryos.retrieval;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A Source the reader may read from, whatever its state. {@code readableDocuments} counts the Documents the reader
 * may open there, including while the Source is paused and its Documents are out of Search. {@code groups} are the
 * names of the reader's Groups granted a PRIVATE Source, sorted, and empty otherwise. Error details, schedules and
 * other Groups are never part of it.
 */
public record ShelfSource(UUID id, String name, SourceType type, SourceAccess access, Status status,
        long readableDocuments, @Nullable Instant lastSucceededAt, List<String> groups, @Nullable String managerName) {
    public ShelfSource {
        groups = List.copyOf(groups);
    }

    /** The Source status a reader sees; a Source still pausing already reads as paused. */
    public enum Status { NOT_STARTED, INDEXING, ACTIVE, PAUSED, FAILED }
}
