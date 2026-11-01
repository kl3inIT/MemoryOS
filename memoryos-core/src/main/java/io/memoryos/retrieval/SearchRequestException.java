package io.memoryos.retrieval;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

public final class SearchRequestException extends BusinessException {
    public SearchRequestException() {
        this("Enter a query of at most 1000 characters and valid search filters.", "Invalid search request");
    }

    SearchRequestException(String safeMessage, String diagnosticMessage) {
        super("SEARCH_REQUEST_INVALID", FailureCategory.VALIDATION, safeMessage, diagnosticMessage);
    }
}
