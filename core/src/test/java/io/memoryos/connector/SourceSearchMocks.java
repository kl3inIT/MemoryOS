package io.memoryos.connector;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.memoryos.document.DocumentId;
import io.memoryos.shared.TenantId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentMatchers;

/** Mock set-up shared by search tests that stub {@link SourceSearchService} one document at a time. */
public final class SourceSearchMocks {
    private SourceSearchMocks() { }

    /** Answers the page reads of the mock from its single-document stubs, so both always agree. */
    public static void answerPagesFromSingleDocuments(SourceSearchService mock) {
        when(mock.indexMetadata(any(TenantId.class), ArgumentMatchers.<Map<DocumentId, UUID>>any())).thenAnswer(call -> {
            TenantId tenant = call.getArgument(0);
            Map<DocumentId, UUID> generations = call.getArgument(1);
            var result = new HashMap<DocumentId, List<DocumentSourceMetadata>>();
            generations.forEach((document, generation) -> result.put(document, mock.indexMetadata(tenant, document, generation)));
            return result;
        });
        when(mock.indexAccess(any(TenantId.class), ArgumentMatchers.<Collection<DocumentId>>any())).thenAnswer(call -> {
            TenantId tenant = call.getArgument(0);
            Collection<DocumentId> documents = call.getArgument(1);
            var result = new HashMap<DocumentId, DocumentAccess>();
            documents.forEach(document -> result.put(document, mock.indexAccess(tenant, document)));
            return result;
        });
    }
}
