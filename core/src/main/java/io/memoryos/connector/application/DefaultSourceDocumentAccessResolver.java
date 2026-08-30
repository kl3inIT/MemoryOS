package io.memoryos.connector.application;

import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.document.DocumentId;
import io.memoryos.identity.ActorId;
import io.memoryos.tenant.TenantAccessResolver;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultSourceDocumentAccessResolver implements SourceDocumentAccessResolver {

    private final TenantAccessResolver tenants;
    private final JdbcSourceDocumentRepository documents;

    public DefaultSourceDocumentAccessResolver(
            TenantAccessResolver tenants,
            JdbcSourceDocumentRepository documents
    ) {
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canRead(ActorId actorId, DocumentId documentId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        return tenants.findActiveTenant(actorId)
                .map(tenantId -> documents.hasEligibleMapping(tenantId, documentId))
                .orElse(false);
    }
}
