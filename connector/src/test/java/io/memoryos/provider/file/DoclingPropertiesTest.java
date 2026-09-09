package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DoclingPropertiesTest {
    @Test
    void rejectsPagesAboveServeLimit() {
        assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, null, 201));
        assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, null, -1));
    }

    @Test
    void rejectsTimeoutAboveApplicationLimitOrNonpositive() {
        for (var timeout : new Duration[]{Duration.ofMinutes(15).plusNanos(1), Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, timeout, 200));
        }
    }
}
