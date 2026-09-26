package io.memoryos.connector.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
    void fileWithProviderIdentityFailsClosed() {
        assertFalse(authority.current(SourceType.FILE, tenant, source, 1L));
        verifyNoInteractions(googleDrive, sharePoint);
    }
}
