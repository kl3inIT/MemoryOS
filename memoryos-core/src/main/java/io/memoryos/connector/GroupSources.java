package io.memoryos.connector;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Sources associated with one Group, with the subset the caller may remove from that Group. The removable set is a
 * browser hint projected from the removal guard; the removal command checks again.
 */
public record GroupSources(List<SourceSummary> sources, Set<SourceId> removableSourceIds) {

    public GroupSources {
        sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
        removableSourceIds = Set.copyOf(Objects.requireNonNull(removableSourceIds, "removableSourceIds must not be null"));
    }
}
