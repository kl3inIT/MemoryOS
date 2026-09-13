package io.memoryos.connector.application;

import io.memoryos.connector.GoogleDriveAclService;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.persistence.JdbcGoogleDriveAclRepository;
import io.memoryos.connector.persistence.JdbcSourceQueryRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantId;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultGoogleDriveAclService implements GoogleDriveAclService {
    private final JdbcGoogleDriveAclRepository acls;
    private final IamAuthorization authorization;
    private final JdbcSourceQueryRepository sources;

    public DefaultGoogleDriveAclService(JdbcGoogleDriveAclRepository acls, IamAuthorization authorization,
            JdbcSourceQueryRepository sources) {
        this.acls = acls;
        this.authorization = authorization;
        this.sources = sources;
    }

    @Override
    public Page list(ActorId actor, SourceId source, Query query) {
        TenantId tenant = readableTenant(actor, source);
        if (query.size() < 1 || query.size() > 100)
            throw SourceException.invalid("ACL page size must be between 1 and 100.", "invalid ACL page size");
        if (query.query() != null && query.query().length() > 256)
            throw SourceException.invalid("ACL search must not exceed 256 characters.", "invalid ACL search length");
        return acls.list(tenant, source, query);
    }

    @Override
    public File get(ActorId actor, SourceId source, String fileId) {
        TenantId tenant = readableTenant(actor, source);
        if (fileId == null || !fileId.matches("[A-Za-z0-9_-]{1,256}"))
            throw SourceException.invalid("Invalid Google Drive file ID.", "invalid ACL file ID");
        return acls.get(tenant, source, fileId).orElseThrow(SourceException::notFound);
    }

    private TenantId readableTenant(ActorId actor, SourceId source) {
        var access = authorization.require(Objects.requireNonNull(actor), IamCapability.SOURCES_READ, true);
        var capabilities = authorization.effectiveCapabilities(actor);
        var summary = sources.summary(access.tenantId(), actor, Objects.requireNonNull(source),
                capabilities.contains(IamCapability.SOURCES_READ),
                capabilities.contains(IamCapability.SOURCES_MANAGE),
                capabilities.contains(IamCapability.SOURCES_DELETE));
        if (summary.type() != SourceType.GOOGLE_DRIVE) throw SourceException.notFound();
        return access.tenantId();
    }
}
