package io.memoryos.connector.adapter;

import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * Reads the {@code Retry-After} header a provider sends with a throttled (429) or unavailable (503) response:
 * either delay-seconds or an HTTP date. A wait longer than an hour is capped, so a malformed or hostile value
 * cannot park a synchronization for days.
 */
public final class RetryAfter {
    static final Duration MAX = Duration.ofHours(1);

    private RetryAfter() {
    }

    public static @Nullable Duration of(HttpHeaders headers, Clock clock) {
        return parse(headers.firstValue("Retry-After").orElse(null), clock);
    }

    static @Nullable Duration parse(@Nullable String value, Clock clock) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.strip();
        Duration wait;
        if (trimmed.chars().allMatch(Character::isDigit)) {
            if (trimmed.length() > 9) return MAX;
            wait = Duration.ofSeconds(Long.parseLong(trimmed));
        } else {
            try {
                var at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                wait = Duration.between(clock.instant(), at);
            } catch (DateTimeParseException exception) {
                return null;
            }
        }
        if (wait.isNegative() || wait.isZero()) return null;
        return wait.compareTo(MAX) > 0 ? MAX : wait;
    }
}
