package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchRequestTest {
    @Test
    void rejectsNullAndMalformedMediaTypesAsValidationFailures() {
        for (var types : List.of(Arrays.asList("text/plain", null), List.of("invalid"))) {
            assertThrows(SearchRequestException.class, () -> new SearchRequest("query", types, null, 0, 10));
        }
    }

    @Test
    void ownsAnImmutableCopyAfterValidationAndAcceptsOmittedFilters() {
        var types = new ArrayList<>(List.of("text/plain"));
        var request = new SearchRequest(" query ", types, null, 0, 10);
        types.clear();
        assertEquals(List.of("text/plain"), request.mediaTypes());
        assertEquals("query", request.query());
        assertThrows(UnsupportedOperationException.class, () -> request.mediaTypes().clear());
        assertEquals(List.of(), new SearchRequest("query", null, null, 0, 10).mediaTypes());
    }
}
