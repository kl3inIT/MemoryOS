package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentOriginalServiceTest {
    private static final byte[] PDF = "%PDF-1.4\n%fixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final TenantAccessResolver tenants = mock(TenantAccessResolver.class);
    private final IamAuthorization authorization = mock(IamAuthorization.class);
    private final SourceDocumentAccessResolver access = mock(SourceDocumentAccessResolver.class);
    private final DocumentChunkPort documents = mock(DocumentChunkPort.class);
    private final OpenSearchIndexService search = mock(OpenSearchIndexService.class);
    private final SourceSearchService sources = mock(SourceSearchService.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final DocumentOriginalService service =
            new DocumentOriginalService(tenants, authorization, access, documents, search, sources, storage);

    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID document = UUID.randomUUID();
    private final UUID generation = UUID.randomUUID();
    private final StoredObjectReference reference = reference(PDF.length);

    @BeforeEach
    void readable() {
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        when(search.identity()).thenReturn("index");
        when(access.canRead(actor, new DocumentId(document))).thenReturn(true);
        when(documents.isCurrent(tenant, new DocumentId(document), generation, "index")).thenReturn(true);
        when(sources.originalPdf(tenant, actor, document)).thenReturn(Optional.of(reference));
    }

    @Test
    void citationStreamsTheVerifiedOriginalWithoutSearchAuthority() throws Exception {
        var closed = new AtomicBoolean();
        when(storage.open(reference.key())).thenReturn(content(reference.metadata(), PDF, closed));
        try (var pdf = service.citationPdf(actor, document, generation)) {
            assertArrayEquals(PDF, pdf.inputStream().readAllBytes());
        }
        assertTrue(closed.get());
        verify(authorization, org.mockito.Mockito.never()).require(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void searchRequiresSearchReadBeforeAndAfterOpening() {
        when(storage.open(reference.key())).thenReturn(content(reference.metadata(), PDF, new AtomicBoolean()));
        service.searchPdf(actor, document, generation).close();
        verify(authorization, org.mockito.Mockito.times(2)).require(actor, IamCapability.SEARCH_READ, false);
    }

    @Test
    void rejectsChangedObjectsNonPdfBytesStaleGenerationsAndRevocationDuringOpen() {
        var closed = new AtomicBoolean();
        when(storage.open(reference.key())).thenReturn(content(reference(PDF.length + 1).metadata(), PDF, closed));
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationPdf(actor, document, generation));
        assertTrue(closed.get());

        var html = "<html>".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var htmlClosed = new AtomicBoolean();
        when(storage.open(reference.key())).thenReturn(content(reference.metadata(), html, htmlClosed));
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationPdf(actor, document, generation));
        assertTrue(htmlClosed.get());

        var revokedClosed = new AtomicBoolean();
        when(storage.open(reference.key())).thenAnswer(_ -> {
            when(access.canRead(actor, new DocumentId(document))).thenReturn(false);
            return content(reference.metadata(), PDF, revokedClosed);
        });
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationPdf(actor, document, generation));
        assertTrue(revokedClosed.get());

        when(access.canRead(actor, new DocumentId(document))).thenReturn(true);
        when(documents.isCurrent(eq(tenant), any(), eq(UUID.fromString(generation.toString())), anyString())).thenReturn(false);
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationPdf(actor, document, generation));
    }

    @Test
    void rejectsMissingOrOversizedOriginalsBeforeOpeningStorage() {
        when(sources.originalPdf(tenant, actor, document)).thenReturn(Optional.empty());
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationPdf(actor, document, generation));
        when(sources.originalPdf(tenant, actor, document)).thenReturn(Optional.of(reference(DocumentOriginalService.MAX_BYTES + 1)));
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationPdf(actor, document, generation));
        verify(storage, org.mockito.Mockito.never()).open(any());
    }

    private static StoredObjectReference reference(long size) {
        return new StoredObjectReference(new StoredObjectId(UUID.nameUUIDFromBytes("pdf".getBytes())),
                new ObjectKey("raw/tenant/pdf"), "handbook.pdf",
                new ObjectMetadata(size, "application/pdf", new ContentSha256("a".repeat(64))));
    }

    private static ObjectContent content(ObjectMetadata metadata, byte[] bytes, AtomicBoolean closed) {
        var input = new ByteArrayInputStream(bytes);
        return new ObjectContent() {
            @Override public ObjectMetadata metadata() { return metadata; }
            @Override public InputStream inputStream() { return input; }
            @Override public void close() { closed.set(true); }
        };
    }
}
