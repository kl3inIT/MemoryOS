package io.memoryos.connector.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceType;
import io.memoryos.shared.TenantId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceAdapterRegistryTest {
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final SourceId source = new SourceId(UUID.randomUUID());

    @Test
    void everyExternalProviderHasExactlyOneSynchronizationAdapterOrStartupFails() {
        var drive = SourceSyncAdapters.credentials(SourceType.GOOGLE_DRIVE, (_, _, _) -> true);
        var sharePoint = SourceSyncAdapters.credentials(SourceType.SHAREPOINT, (_, _, _) -> true);

        assertEquals("No synchronization adapter for SHAREPOINT", assertThrows(IllegalStateException.class,
                () -> new SourceSyncAdapterRegistry(List.of(drive))).getMessage());
        assertEquals("Two synchronization adapters for GOOGLE_DRIVE", assertThrows(IllegalStateException.class,
                () -> new SourceSyncAdapterRegistry(List.of(drive, sharePoint, drive))).getMessage());
        assertEquals("A synchronization adapter for FILE", assertThrows(IllegalStateException.class,
                () -> new SourceSyncAdapterRegistry(List.of(drive, sharePoint,
                        SourceSyncAdapters.credentials(SourceType.FILE, (_, _, _) -> true)))).getMessage());
    }

    @Test
    void everyExternalProviderHasExactlyOneSelectionAdapterOrStartupFails() {
        var drive = selection(SourceType.GOOGLE_DRIVE);
        var sharePoint = selection(SourceType.SHAREPOINT);

        assertEquals("No selection adapter for GOOGLE_DRIVE", assertThrows(IllegalStateException.class,
                () -> new SourceSelectionAdapterRegistry(List.of(sharePoint))).getMessage());
        assertEquals("Two selection adapters for SHAREPOINT", assertThrows(IllegalStateException.class,
                () -> new SourceSelectionAdapterRegistry(List.of(drive, sharePoint, sharePoint))).getMessage());
        assertEquals(drive, new SourceSelectionAdapterRegistry(List.of(drive, sharePoint)).require(SourceType.GOOGLE_DRIVE));
    }

    @Test
    void aTypeWithoutAProviderHasNoAuthorityAndNoProviderPermissions() {
        var registry = SourceSyncAdapters.registry(
                SourceSyncAdapters.credentials(SourceType.GOOGLE_DRIVE, (_, _, revision) -> revision == 7),
                SourceSyncAdapters.credentials(SourceType.SHAREPOINT, (_, _, _) -> true));

        assertFalse(registry.credentialCurrent(SourceType.FILE, tenant, source, 7));
        assertTrue(registry.credentialCurrent(SourceType.GOOGLE_DRIVE, tenant, source, 7));
        assertFalse(registry.credentialCurrent(SourceType.GOOGLE_DRIVE, tenant, source, 8));
        assertTrue(registry.permissionSync(SourceType.GOOGLE_DRIVE));
        assertFalse(registry.permissionSync(SourceType.SHAREPOINT));
        assertFalse(registry.permissionSync(SourceType.FILE));
    }

    @Test
    void everyExternalProviderNamesItsSelectionOperation() {
        for (var type : SourceType.values()) {
            if (!type.external()) continue;
            assertEquals("VALIDATE_" + type.name() + "_SELECTION", SourceOperationType.selectionValidation(type).name());
        }
    }

    private static SourceSelectionAdapter selection(SourceType type) {
        var adapter = mock(SourceSelectionAdapter.class);
        when(adapter.type()).thenReturn(type);
        return adapter;
    }
}
