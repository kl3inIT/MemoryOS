package io.memoryos.retrieval.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.retrieval.SearchFilters;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The index clause that erased every Google Drive document for a question naming a period: those items
 * carry no source dates, and a bare range never matches an absent field.
 */
class UndatedDateClauseTest {
    private static final SearchFilters.Interval SEPTEMBER = new SearchFilters.Interval(
            Instant.parse("2025-09-01T00:00:00Z"), Instant.parse("2025-09-30T23:59:59Z"));

    @Test
    void a_widened_clause_also_matches_documents_without_that_field() {
        var clause = OpenSearchIndexService.dateRange("source_metadata.created_at", SEPTEMBER, true);

        @SuppressWarnings("unchecked")
        var bool = (Map<String, Object>) clause.get("bool");
        @SuppressWarnings("unchecked")
        var should = (List<Object>) bool.get("should");
        assertEquals(1, bool.get("minimum_should_match"));
        assertEquals(2, should.size());
        assertTrue(should.get(0).toString().contains("range"));
        assertTrue(should.get(1).toString().contains("must_not"), "the second branch admits undated documents");
        assertTrue(should.get(1).toString().contains("source_metadata.created_at"));
    }

    @Test
    void a_strict_clause_stays_a_plain_range() {
        var clause = OpenSearchIndexService.dateRange("source_metadata.updated_at", SEPTEMBER, false);

        assertTrue(clause.containsKey("range"));
        assertEquals(Map.of("gte", "2025-09-01T00:00:00Z", "lte", "2025-09-30T23:59:59Z"),
                clause.get("range") instanceof Map<?, ?> range ? range.get("source_metadata.updated_at") : null);
    }
}
