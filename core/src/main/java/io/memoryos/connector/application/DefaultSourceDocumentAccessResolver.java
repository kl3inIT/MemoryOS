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
                .map(tenantId -> sourceDocuments.hasEligibleMapping(tenantId, documentId))
                .orElse(false);
    }
}
