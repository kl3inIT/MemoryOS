package io.memoryos.connector.sync.persistence;

/**
 * The provider tables a synchronization attempt is fenced against: the provider's Source row, whose scope
 * revision and generation the attempt captured, and the provider credential row whose revision it captured.
 * Both provider tables carry {@code generation}, {@code sync_interval_minutes}, {@code next_sync_at},
 * {@code last_synced_at} and {@code sync_paused}; only the scope revision column is named differently. The names are
 * spliced into SQL, so they come only from these constants.
 */
public enum SyncTarget {
    GOOGLE_DRIVE("google_drive_sources", "revision", "google_drive_credentials"),
    SHAREPOINT("sharepoint_sources", "scope_revision", "sharepoint_credentials");

    private final String sourceTable;
    private final String scopeRevisionColumn;
    private final String credentialTable;

    SyncTarget(String sourceTable, String scopeRevisionColumn, String credentialTable) {
        this.sourceTable = WorkLeases.identifier(sourceTable);
        this.scopeRevisionColumn = WorkLeases.identifier(scopeRevisionColumn);
        this.credentialTable = WorkLeases.identifier(credentialTable);
    }

    public String sourceTable() {
        return sourceTable;
    }

    public String scopeRevisionColumn() {
        return scopeRevisionColumn;
    }

    public String credentialTable() {
        return credentialTable;
    }
}
