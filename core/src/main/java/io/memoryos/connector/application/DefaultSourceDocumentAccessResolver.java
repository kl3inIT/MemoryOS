package io.memoryos.connector.application;

import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.document.DocumentId;
import io.memoryos.identity.ActorId;
import io.memoryos.organization.OrganizationAccessResolver;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultSourceDocumentAccessResolver implements SourceDocumentAccessResolver {

    private final OrganizationAccessResolver organizations;
    private final JdbcSourceDocumentRepository documents;

    public DefaultSourceDocumentAccessResolver(
            OrganizationAccessResolver organizations,
            JdbcSourceDocumentRepository documents
    ) {
        this.organizations = Objects.requireNonNull(organizations, "organizations must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canRead(ActorId actorId, DocumentId documentId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        return organizations.findActiveOrganization(actorId)
                .map(organizationId -> documents.hasEligibleMapping(organizationId, documentId))
                .orElse(false);
    }
}
