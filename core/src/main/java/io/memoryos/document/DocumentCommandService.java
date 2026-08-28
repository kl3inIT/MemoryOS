package io.memoryos.document;

import io.memoryos.organization.OrganizationId;

import java.util.List;

import org.jspecify.annotations.Nullable;

public interface DocumentCommandService {

    DocumentId publish(
            OrganizationId organizationId,
            @Nullable DocumentId existingDocumentId,
            DocumentContent content,
            String sourceSha256
    );

    void removeUnreferenced(OrganizationId organizationId, List<DocumentId> documentIds);
}
