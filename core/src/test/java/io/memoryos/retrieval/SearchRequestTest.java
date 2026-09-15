package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.connector.SourceType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchRequestTest {
    @Test
    void rejectsNullAndMalformedMediaTypesAsValidationFailures() {
        for (var types : List.of(Arrays.asList("text/plain", null), List.of("invalid"))) {
            assertThrows(SearchRequestException.class, () -> new SearchRequest("query", types, null, 0, 10, List.of()));
        }
    }

    @Test
    void ownsAnImmutableCopyAfterValidationAndAcceptsOmittedFilters() {
        var types = new ArrayList<>(List.of("text/plain"));
        var request = new SearchRequest(" query ", types, null, 0, 10, null);
        types.clear();
        assertEquals(List.of("text/plain"), request.mediaTypes());
        assertEquals("query", request.query());
        assertThrows(UnsupportedOperationException.class, () -> request.mediaTypes().clear());
        assertEquals(List.of(), new SearchRequest("query", null, null, 0, 10, null).mediaTypes());
        assertEquals(List.of(), request.sourceTypes());
    }

    @Test
    void keepsDistinctConnectorFiltersInOrderAndRejectsNullOrOversizedLists() {
        var request = new SearchRequest("query", null, null, 0, 10,
                List.of(SourceType.GOOGLE_DRIVE, SourceType.GOOGLE_DRIVE, SourceType.FILE));
        assertEquals(List.of(SourceType.GOOGLE_DRIVE, SourceType.FILE), request.sourceTypes());
        assertThrows(UnsupportedOperationException.class, () -> request.sourceTypes().clear());
        assertThrows(SearchRequestException.class,
                () -> new SearchRequest("query", null, null, 0, 10, Arrays.asList(SourceType.FILE, null)));
        assertThrows(SearchRequestException.class,
                () -> new SearchRequest("query", null, null, 0, 10, Collections.nCopies(11, SourceType.FILE)));
    }
}
