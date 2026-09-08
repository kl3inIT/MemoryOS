package io.memoryos.retrieval;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

public final class SearchDocumentUnavailableException extends BusinessException {
    public SearchDocumentUnavailableException() {
        super("SEARCH_DOCUMENT_UNAVAILABLE", FailureCategory.NOT_FOUND,
                "This document is no longer available. Search again for the current version.", "Search document unavailable");
    }
}
