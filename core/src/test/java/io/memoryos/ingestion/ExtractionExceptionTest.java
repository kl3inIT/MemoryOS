package io.memoryos.ingestion;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExtractionExceptionTest {

    @Test
    void requiresFailureClassificationAtConstruction() {
        assertThrows(NullPointerException.class, () -> {
            throw new ExtractionException(null, "failed");
        });
        assertThrows(NullPointerException.class, () -> {
            throw new ExtractionException(null, "failed", new Exception());
        });
    }
}
