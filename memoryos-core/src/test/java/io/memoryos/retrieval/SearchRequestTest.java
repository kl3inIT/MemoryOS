package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.connector.SourceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchRequestTest {
    @Test
    void rejectsNullAndMalformedMediaTypesAsValidationFailures() {
        for (var types : List.of(Arrays.asList("text/plain", null), List.of("invalid"))) {
            assertThrows(SearchRequestException.class, () -> new SearchRequest("query", types, null, null, 0, 10, List.of(), List.of()));
        }
    }

    @Test
    void ownsAnImmutableCopyAfterValidationAndAcceptsOmittedFilters() {
        var types = new ArrayList<>(List.of("text/plain"));
        var request = new SearchRequest(" query ", types, null, null, 0, 10, null, List.of());
        types.clear();
        assertEquals(List.of("text/plain"), request.mediaTypes());
        assertEquals("query", request.query());
        assertThrows(UnsupportedOperationException.class, () -> request.mediaTypes().clear());
        assertEquals(List.of(), new SearchRequest("query", null, null, null, 0, 10, null, List.of()).mediaTypes());
        assertEquals(List.of(), request.sourceTypes());
    }

    @Test
    void keepsDistinctConnectorFiltersInOrderAndRejectsNullOrOversizedLists() {
        var request = new SearchRequest("query", null, null, null, 0, 10,
                List.of(SourceType.GOOGLE_DRIVE, SourceType.GOOGLE_DRIVE, SourceType.FILE), List.of());
        assertEquals(List.of(SourceType.GOOGLE_DRIVE, SourceType.FILE), request.sourceTypes());
        assertThrows(UnsupportedOperationException.class, () -> request.sourceTypes().clear());
        assertThrows(SearchRequestException.class,
                () -> new SearchRequest("query", null, null, null, 0, 10, Arrays.asList(SourceType.FILE, null), List.of()));
        assertThrows(SearchRequestException.class,
                () -> new SearchRequest("query", null, null, null, 0, 10, Collections.nCopies(11, SourceType.FILE), List.of()));
    }

    @Test
    void anUpdateWindowIsOptionalAndMustNotEndBeforeItStarts() {
        var from = Instant.parse("2026-08-01T00:00:00Z");
        assertThrows(SearchRequestException.class,
                () -> new SearchRequest("query", null, from, from.minusSeconds(1), 0, 10, null, List.of()));
        assertNull(new SearchRequest("query", null, null, null, 0, 10, null, List.of()).updated());
        assertEquals(new SearchFilters.Interval(from, null),
                new SearchRequest("query", null, from, null, 0, 10, null, List.of()).updated());
    }
}
