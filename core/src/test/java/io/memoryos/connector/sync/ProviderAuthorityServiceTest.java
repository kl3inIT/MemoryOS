package io.memoryos.connector.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.connector.googledrive.GoogleDriveConnectionService;
import io.memoryos.connector.sharepoint.SharePointConnectionService;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceType;
import io.memoryos.shared.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderAuthorityServiceTest {
    private final GoogleDriveConnectionService googleDrive = mock(GoogleDriveConnectionService.class);
    private final SharePointConnectionService sharePoint = mock(SharePointConnectionService.class);
    private final ProviderAuthorityService authority =
            new ProviderAuthorityService(googleDrive, sharePoint);
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final SourceId source = new SourceId(UUID.randomUUID());

    @Test
    void routesSharePointAuthorityToSharePoint() {
        when(sharePoint.current(tenant, source, 7L)).thenReturn(true);

        assertTrue(authority.current(SourceType.SHAREPOINT, tenant, source, 7L));

        verify(sharePoint).current(tenant, source, 7L);
        verifyNoInteractions(googleDrive);
    }

    @Test
    void routesGoogleDriveAuthorityToGoogleDrive() {
        when(googleDrive.current(tenant, source, 3L)).thenReturn(true);

        assertTrue(authority.current(SourceType.GOOGLE_DRIVE, tenant, source, 3L));

        verify(googleDrive).current(tenant, source, 3L);
        verifyNoInteractions(sharePoint);
    }

    @Test
    void fileWithProviderIdentityFailsClosed() {
        assertFalse(authority.current(SourceType.FILE, tenant, source, 1L));
        verifyNoInteractions(googleDrive, sharePoint);
    }
}
