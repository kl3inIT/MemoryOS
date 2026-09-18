package io.memoryos.connector.application;

import io.memoryos.connector.GoogleDriveConnectionService;
import io.memoryos.connector.ProviderAuthorityService;
import io.memoryos.connector.SharePointConnectionService;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceType;
import io.memoryos.iam.tenant.TenantId;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Explicit provider dispatch for the connector types MemoryOS currently implements. */
@Service
public final class DefaultProviderAuthorityService implements ProviderAuthorityService {
    private final GoogleDriveConnectionService googleDrive;
    private final SharePointConnectionService sharePoint;

    public DefaultProviderAuthorityService(GoogleDriveConnectionService googleDrive,
            SharePointConnectionService sharePoint) {
        this.googleDrive = Objects.requireNonNull(googleDrive, "googleDrive must not be null");
        this.sharePoint = Objects.requireNonNull(sharePoint, "sharePoint must not be null");
    }

    @Override
    public boolean current(SourceType sourceType, TenantId tenantId, SourceId sourceId, long credentialRevision) {
        return switch (Objects.requireNonNull(sourceType, "sourceType must not be null")) {
            case GOOGLE_DRIVE -> googleDrive.current(tenantId, sourceId, credentialRevision);
            case SHAREPOINT -> sharePoint.current(tenantId, sourceId, credentialRevision);
            case FILE -> false;
        };
    }
}
