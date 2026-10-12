package io.memoryos.connector;

public enum SourceAccess {
    /** Every active Tenant member. */
    PUBLIC,
    /** Members of the Groups associated with the Source. */
    PRIVATE,
    /** Readers granted by the provider's retained per-file permissions; Google Drive only. */
    SYNC
}
