package io.memoryos.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class TurnFailureTest {

    /** Codes are stored on failed answers and read by the web client: every value is pinned byte for byte. */
    @Test
    void everyCodeAndItsVisibilityAreStable() {
        var expected = new LinkedHashMap<TurnFailure, Map.Entry<String, Boolean>>();
        expected.put(TurnFailure.OUTPUT_LIMIT, Map.entry("CHAT_OUTPUT_LIMIT", true));
        expected.put(TurnFailure.CYCLE_LIMIT, Map.entry("CHAT_CYCLE_LIMIT", true));
        expected.put(TurnFailure.BUDGET_EXCEEDED, Map.entry("CHAT_BUDGET_EXCEEDED", true));
        expected.put(TurnFailure.MODEL_UNAVAILABLE, Map.entry("CHAT_MODEL_UNAVAILABLE", true));
        expected.put(TurnFailure.INCOMPLETE_RESPONSE, Map.entry("CHAT_INCOMPLETE_RESPONSE", true));
        expected.put(TurnFailure.LAST_CYCLE_TOOL_CALL, Map.entry("CHAT_LAST_CYCLE_TOOL_CALL", true));
        expected.put(TurnFailure.UNSUPPORTED_OPTIONS, Map.entry("CHAT_UNSUPPORTED_OPTIONS", true));
        expected.put(TurnFailure.EMPTY_RESPONSE, Map.entry("CHAT_EMPTY_RESPONSE", true));
        expected.put(TurnFailure.CONTEXT_LIMIT, Map.entry("CHAT_CONTEXT_LIMIT", true));
        expected.put(TurnFailure.MODEL_OUTPUT_LIMIT, Map.entry("CHAT_MODEL_OUTPUT_LIMIT", true));
        expected.put(TurnFailure.DEADLINE, Map.entry("CHAT_DEADLINE", false));
        expected.put(TurnFailure.PROVIDER_UNAVAILABLE, Map.entry("CHAT_PROVIDER_UNAVAILABLE", false));

        var actual = Arrays.stream(TurnFailure.values()).collect(Collectors.toMap(failure -> failure,
                failure -> Map.entry(failure.code(), failure.reported()), (first, second) -> first, LinkedHashMap::new));
        assertEquals(expected, actual);
    }

    @Test
    void exceptionCarriesOnlyTheCode() {
        for (var failure : TurnFailure.values()) {
            var exception = failure.exception();
            assertEquals(failure, exception.failure());
            assertEquals(failure.code(), exception.code());
            assertEquals(failure.code(), exception.getMessage());
            // Embabel's retry policy does not retry an IllegalStateException.
            assertTrue(exception instanceof IllegalStateException);
        }
    }

    @Test
    void reportedFailureIsFoundThroughCausesPastUnreportedOnes() {
        var nested = TurnFailure.DEADLINE.exception();
        nested.initCause(TurnFailure.CONTEXT_LIMIT.exception());
        assertEquals(TurnFailure.CONTEXT_LIMIT, TurnFailureException.reportedIn(new RuntimeException(nested)).orElseThrow());
        assertTrue(TurnFailureException.reportedIn(TurnFailure.DEADLINE.exception()).isEmpty());
        assertTrue(TurnFailureException.reportedIn(new IllegalStateException("CHAT_OUTPUT_LIMIT")).isEmpty());
    }
}
