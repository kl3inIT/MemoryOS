package io.memoryos.connector.sync.persistence;

/**
 * The provider tables a synchronization attempt is fenced against: the provider's Source row, whose scope
 * revision and generation the attempt captured, and the provider credential row whose revision it captured.
 * Both provider tables carry {@code generation}, {@code sync_interval_minutes}, {@code next_sync_at},
 * {@code last_synced_at} and {@code sync_paused}; only the scope revision column is named differently.
 */
public record SyncTarget(String sourceTable, String scopeRevisionColumn, String credentialTable) {
    public static final SyncTarget GOOGLE_DRIVE =
            new SyncTarget("google_drive_sources", "revision", "google_drive_credentials");
    public static final SyncTarget SHAREPOINT =
            new SyncTarget("sharepoint_sources", "scope_revision", "sharepoint_credentials");
}
