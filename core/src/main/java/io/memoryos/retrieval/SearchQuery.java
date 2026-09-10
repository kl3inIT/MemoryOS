package io.memoryos.retrieval;

import org.jspecify.annotations.NonNull;

/** Backend query group, not an index mode: all queries use hybrid retrieval; weight applies to RRF. */
public record SearchQuery(String text, boolean keyword, double weight) {
    public SearchQuery {
        if (text == null || text.isBlank() || text.length() > 2000 || !Double.isFinite(weight) || weight <= 0 || weight > 2)
            throw new SearchRequestException();
        text = text.strip();
    }
    @Override public @NonNull String toString() { return "SearchQuery[redacted]"; }
}
