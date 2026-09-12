package io.memoryos.retrieval;

import io.memoryos.connector.SourceType;
import io.memoryos.connector.DocumentSourceMetadata;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Query restrictions, never authorization. Both endpoints of each interval are inclusive. */
public record SearchFilters(Set<SourceType> sources, @Nullable Interval created, @Nullable Interval updated) {
    public static final SearchFilters NONE = new SearchFilters(Set.of(), null, null);
    public SearchFilters { sources = Set.copyOf(sources); }

    public boolean matches(DocumentSourceMetadata origin) {
        return (sources.isEmpty() || sources.contains(origin.type()))
                && (created == null || created.contains(origin.createdAt()))
                && (updated == null || updated.contains(origin.updatedAt()));
    }

    public record Interval(@Nullable Instant from, @Nullable Instant to) {
        public Interval {
            if (from == null && to == null || from != null && to != null && from.isAfter(to))
                throw new SearchRequestException();
        }
        public boolean contains(@Nullable Instant instant) {
            return instant != null && (from == null || !instant.isBefore(from)) && (to == null || !instant.isAfter(to));
        }
    }

    /** An inconsistent inference must not erase an explicit user restriction. */
    public static @Nullable Interval intersect(@Nullable Interval explicit, @Nullable Interval inferred) {
        if (explicit == null) return inferred;
        if (inferred == null) return explicit;
        Instant from = explicit.from() == null ? inferred.from() : inferred.from() == null ? explicit.from()
                : explicit.from().isAfter(inferred.from()) ? explicit.from() : inferred.from();
        Instant to = explicit.to() == null ? inferred.to() : inferred.to() == null ? explicit.to()
                : explicit.to().isBefore(inferred.to()) ? explicit.to() : inferred.to();
        return from != null && to != null && from.isAfter(to) ? explicit : new Interval(from, to);
    }
}
