package io.memoryos.connector.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RetryAfterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void readsDelaySecondsAndHttpDates() {
        assertEquals(Duration.ofSeconds(120), RetryAfter.parse("120", CLOCK));
        assertEquals(Duration.ofSeconds(90), RetryAfter.parse("Fri, 25 Sep 2026 10:01:30 GMT", CLOCK));
    }

    @Test
    void capsLongWaitsAndIgnoresWhatItCannotUse() {
        assertEquals(RetryAfter.MAX, RetryAfter.parse("86400", CLOCK));
        assertEquals(RetryAfter.MAX, RetryAfter.parse("99999999999999999999", CLOCK));
        assertNull(RetryAfter.parse("0", CLOCK));
        assertNull(RetryAfter.parse("Fri, 25 Sep 2026 09:59:00 GMT", CLOCK), "a date in the past asks for no wait");
        assertNull(RetryAfter.parse("soon", CLOCK));
        assertNull(RetryAfter.parse(" ", CLOCK));
        assertNull(RetryAfter.parse(null, CLOCK));
    }
}
