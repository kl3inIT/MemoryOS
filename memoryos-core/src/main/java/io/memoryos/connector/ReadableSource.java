package io.memoryos.connector;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A Source the reader may read from, whatever its state except DELETING. {@code status} is the status a Source
 * summary reports; {@code readableDocuments} counts the eligible Documents the reader may read under the Search
 * document rule, without its Source-status condition. {@code groups} are the names of the reader's Groups granted a
 * PRIVATE Source, sorted, and empty for other Sources; {@code managerName} names the responsible manager.
 */
public record ReadableSource(UUID id, String name, SourceType type, SourceAccess access, SourceStatus status,
        long readableDocuments, @Nullable Instant lastSucceededAt, List<String> groups, @Nullable String managerName) {
    public ReadableSource {
        groups = List.copyOf(groups);
    }
}
