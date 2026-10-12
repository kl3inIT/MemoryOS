package io.memoryos.retrieval;

import io.memoryos.connector.SourceType;
import io.memoryos.connector.DocumentSourceMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Query restrictions, never authorization. Both endpoints of each interval are inclusive. */
public record SearchFilters(Set<SourceType> sources, @Nullable Interval created, @Nullable Interval updated) {
    public static final SearchFilters NONE = new SearchFilters(Set.of(), null, null);
    /** Onyx {@code ASSUMED_DOCUMENT_AGE_DAYS}: how old an open lower bound must be to admit undated documents. */
    public static final Duration ASSUMED_DOCUMENT_AGE = Duration.ofDays(90);
    public SearchFilters { sources = Set.copyOf(sources); }

    public boolean matches(DocumentSourceMetadata origin) {
        return matches(origin, Instant.now());
    }

    /**
     * A missing timestamp must not remove a document, as in Onyx: a document with no creation date cannot be
     * shown to fall outside a window. The one exception keeps a recent window from being flooded by undated
     * documents, so an update window admits them only when it is an old, open-ended lower bound.
     */
    public boolean matches(DocumentSourceMetadata origin, Instant now) {
        return (sources.isEmpty() || sources.contains(origin.type()))
                && (created == null || origin.createdAt() == null || created.contains(origin.createdAt()))
                && (updated == null || (origin.updatedAt() == null
                        ? keepsUndated(updated, now) : updated.contains(origin.updatedAt())));
    }

    /** True when an update window admits documents that carry no update date. */
    public static boolean keepsUndated(@Nullable Interval updated, Instant now) {
        return updated != null && updated.to() == null && updated.from() != null
                && updated.from().isBefore(now.minus(ASSUMED_DOCUMENT_AGE));
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
