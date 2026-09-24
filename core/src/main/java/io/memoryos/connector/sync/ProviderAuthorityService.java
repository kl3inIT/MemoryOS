package io.memoryos.connector.sync;

import io.memoryos.connector.googledrive.GoogleDriveConnectionService;
import io.memoryos.connector.sharepoint.SharePointConnectionService;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceType;
import io.memoryos.shared.TenantId;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Explicit provider dispatch for the connector types MemoryOS currently implements. */
@Service
public final class ProviderAuthorityService {
    private final GoogleDriveConnectionService googleDrive;
    private final SharePointConnectionService sharePoint;

    public ProviderAuthorityService(GoogleDriveConnectionService googleDrive,
            SharePointConnectionService sharePoint) {
        this.googleDrive = Objects.requireNonNull(googleDrive, "googleDrive must not be null");
        this.sharePoint = Objects.requireNonNull(sharePoint, "sharePoint must not be null");
    }

    public boolean current(SourceType sourceType, TenantId tenantId, SourceId sourceId, long credentialRevision) {
        return switch (Objects.requireNonNull(sourceType, "sourceType must not be null")) {
            case GOOGLE_DRIVE -> googleDrive.current(tenantId, sourceId, credentialRevision);
            case SHAREPOINT -> sharePoint.current(tenantId, sourceId, credentialRevision);
            case FILE -> false;
        };
    }
}
