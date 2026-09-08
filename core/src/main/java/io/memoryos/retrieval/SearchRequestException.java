package io.memoryos.retrieval;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

public final class SearchRequestException extends BusinessException {
    public SearchRequestException() {
        super("SEARCH_REQUEST_INVALID", FailureCategory.VALIDATION,
                "Enter a query of at most 1000 characters and valid search filters.", "Invalid search request");
    }
}
