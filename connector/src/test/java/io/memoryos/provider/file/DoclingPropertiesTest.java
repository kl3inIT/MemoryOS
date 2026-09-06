package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DoclingPropertiesTest {
    @Test
    void defaultsMatchPinnedServeLimits() {
        var properties = new DoclingProperties(null, null, null, 0);
        assertEquals(200, properties.maxPages());
        assertEquals(Duration.ofSeconds(300), properties.timeout());
    }

    @Test
    void rejectsPagesAboveServeLimit() {
        assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, null, 201));
        assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, null, -1));
    }

    @Test
    void rejectsTimeoutAboveServeLimitOrNonpositive() {
        for (var timeout : new Duration[]{Duration.ofSeconds(300).plusNanos(1), Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class, () -> new DoclingProperties(null, null, timeout, 200));
        }
    }

    @Test
    void permitsLowerLimitsAndExactServerMaximum() {
        assertDoesNotThrow(() -> new DoclingProperties(null, null, Duration.ofSeconds(1), 1));
        assertDoesNotThrow(() -> new DoclingProperties(null, null, Duration.ofSeconds(300), 200));
    }
}
