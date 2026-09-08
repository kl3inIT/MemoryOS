package io.memoryos.retrieval;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/** An external search dependency failed; never exposes provider payloads or credentials. */
public final class SearchUnavailableException extends BusinessException {
    public SearchUnavailableException() {
        super("SEARCH_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "Search is temporarily unavailable.", "Search dependency unavailable");
    }
}
