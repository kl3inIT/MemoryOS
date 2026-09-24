package io.memoryos.retrieval.settings;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/** An expected refusal of a search settings command; messages are safe to show and carry no provider payload. */
public final class SearchSettingsException extends BusinessException {
    private SearchSettingsException(String code, FailureCategory category, String message) {
        super(code, category, message, message);
    }

    static SearchSettingsException notPermitted() {
        return new SearchSettingsException("SEARCH_SETTINGS_NOT_PERMITTED", FailureCategory.NOT_PERMITTED,
                "Only a model manager of the operating Tenant can change the search configuration.");
    }

    static SearchSettingsException notFound() {
        return new SearchSettingsException("SEARCH_SETTINGS_NOT_FOUND", FailureCategory.NOT_FOUND,
                "The search generation or embedding provider does not exist.");
    }

    static SearchSettingsException invalid(String message) {
        return new SearchSettingsException("SEARCH_SETTINGS_INVALID", FailureCategory.VALIDATION, message);
    }

    static SearchSettingsException futureExists() {
        return new SearchSettingsException("SEARCH_SETTINGS_FUTURE_EXISTS", FailureCategory.CONFLICT,
                "An index is already being rebuilt.");
    }

    static SearchSettingsException incomplete() {
        return new SearchSettingsException("SEARCH_SETTINGS_REBUILD_INCOMPLETE", FailureCategory.CONFLICT,
                "The rebuilt index does not hold every document yet.");
    }

    static SearchSettingsException retentionEnded() {
        return new SearchSettingsException("SEARCH_SETTINGS_RETENTION_ENDED", FailureCategory.CONFLICT,
                "The retention period of this index has ended.");
    }

    static SearchSettingsException staleRevision() {
        return new SearchSettingsException("SEARCH_SETTINGS_STALE_REVISION", FailureCategory.CONFLICT,
                "The embedding provider was changed by someone else.");
    }

    static SearchSettingsException duplicateName() {
        return new SearchSettingsException("SEARCH_SETTINGS_DUPLICATE_NAME", FailureCategory.CONFLICT,
                "Another embedding provider has this name.");
    }

    static SearchSettingsException providerInUse() {
        return new SearchSettingsException("SEARCH_SETTINGS_PROVIDER_IN_USE", FailureCategory.CONFLICT,
                "A search generation still uses this embedding provider.");
    }

    static SearchSettingsException embeddingRejected(String reason) {
        return new SearchSettingsException("SEARCH_SETTINGS_EMBEDDING_REJECTED", FailureCategory.VALIDATION, reason);
    }
}
