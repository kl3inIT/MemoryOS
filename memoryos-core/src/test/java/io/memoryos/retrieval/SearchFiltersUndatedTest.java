package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.connector.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A window must not remove a document whose date is unknown. Google Drive ingestion records no source
 * dates yet, so a strict range erased the whole corpus for any question naming a period.
 */
class SearchFiltersUndatedTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    private static DocumentSourceMetadata origin(@Nullable Instant created, @Nullable Instant updated) {
        return new DocumentSourceMetadata(UUID.randomUUID(), UUID.randomUUID(), SourceType.GOOGLE_DRIVE,
                created, updated, List.of());
    }

    @Test
    void an_undated_origin_survives_a_creation_window() {
        var filters = new SearchFilters(Set.of(), new SearchFilters.Interval(
                Instant.parse("2025-09-01T00:00:00Z"), Instant.parse("2025-09-30T23:59:59Z")), null);

        assertTrue(filters.matches(origin(null, null), NOW));
        assertFalse(filters.matches(origin(Instant.parse("2024-01-01T00:00:00Z"), null), NOW),
                "a dated origin outside the window is still excluded");
    }

    @Test
    void an_undated_origin_survives_an_old_open_update_floor_but_not_a_bounded_window() {
        var cutoff = new SearchFilters(Set.of(), null,
                new SearchFilters.Interval(NOW.minusSeconds(200 * 86400), null));
        var september = new SearchFilters(Set.of(), null, new SearchFilters.Interval(
                Instant.parse("2025-09-01T00:00:00Z"), Instant.parse("2025-09-30T23:59:59Z")));

        // A Persona knowledge cutoff is exactly this shape, and used to return nothing at all.
        assertTrue(cutoff.matches(origin(null, null), NOW));
        assertTrue(SearchFilters.keepsUndated(cutoff.updated(), NOW));
        // A recent bounded window keeps its edge, as Onyx does, so it is not flooded by undated documents.
        assertFalse(september.matches(origin(null, null), NOW));
        assertFalse(SearchFilters.keepsUndated(september.updated(), NOW));
    }

    @Test
    void a_recent_open_floor_still_excludes_undated_documents() {
        var recent = new SearchFilters(Set.of(), null,
                new SearchFilters.Interval(NOW.minusSeconds(10 * 86400), null));

        assertFalse(recent.matches(origin(null, null), NOW));
    }
}
