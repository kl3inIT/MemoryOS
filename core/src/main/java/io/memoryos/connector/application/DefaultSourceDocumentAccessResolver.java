package io.memoryos.connector.application;

import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultSourceDocumentAccessResolver implements SourceDocumentAccessResolver {

    private final TenantAccessResolver tenantAccess;
    private final JdbcSourceDocumentRepository sourceDocuments;

    public DefaultSourceDocumentAccessResolver(
            TenantAccessResolver tenantAccess,
            JdbcSourceDocumentRepository sourceDocuments
    ) {
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess must not be null");
        this.sourceDocuments = Objects.requireNonNull(sourceDocuments, "sourceDocuments must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canRead(ActorId actorId, DocumentId documentId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        return tenantAccess.findActiveTenant(actorId)
                .map(tenantId -> sourceDocuments.hasEligibleMapping(tenantId, actorId, documentId))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> readableDocuments(ActorId actorId, List<UUID> documents) {
        return tenantAccess.findActiveTenant(actorId).map(tenant -> sourceDocuments.readableDocuments(tenant, actorId, documents))
                .orElse(Set.of());
    }
}
