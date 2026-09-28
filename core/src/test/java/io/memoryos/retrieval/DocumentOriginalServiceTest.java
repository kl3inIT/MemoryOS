package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentId;
import io.memoryos.document.SpreadsheetPreview;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectRangeContent;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.retrieval.DocumentOriginalService.ByteRange;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class DocumentOriginalServiceTest {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final byte[] PDF = "%PDF-1.4\n%fixture".getBytes(StandardCharsets.US_ASCII);

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
        when(sources.originals(tenant, actor, Set.of(document))).thenReturn(Map.of(document, reference));
        when(storage.inspect(reference.key())).thenReturn(reference.metadata());
    }

    @Test
    void citationStreamsTheVerifiedOriginalWithoutSearchAuthority() throws Exception {
        var closed = new AtomicBoolean();
        when(storage.open(reference.key())).thenReturn(content(reference.metadata(), PDF, closed));
        try (var pdf = service.citationOriginal(actor, document, generation, null)) {
            assertNull(pdf.range());
            assertArrayEquals(PDF, pdf.inputStream().readAllBytes());
        }
        assertTrue(closed.get());
        verify(authorization, never()).require(any(), any(), ArgumentMatchers.anyBoolean());
    }

    @Test
    void sandboxOriginalsKeepReadableBoundedFilesOfAnyTypeAndRecheckOnOpen() throws Exception {
        UUID hidden = UUID.randomUUID(), large = UUID.randomUUID();
        var sheet = new StoredObjectReference(new StoredObjectId(UUID.randomUUID()), new ObjectKey("raw/tenant/sheet"),
                "sales.xlsx", new ObjectMetadata(4, "application/vnd.ms-excel", new ContentSha256("d".repeat(64))));
        when(access.readableDocuments(actor, List.of(document, hidden, large))).thenReturn(Set.of(document, large));
        when(sources.originals(tenant, actor, Set.of(document, large))).thenReturn(Map.of(
                document, sheet, large, reference(DocumentOriginalService.MAX_BYTES + 1)));

        assertEquals(Map.of(document, sheet),
                service.citationOriginals(actor, List.of(document, hidden, large)));

        // A non-PDF original has no magic to check; the stored size is still verified on open.
        when(sources.originals(tenant, actor, Set.of(document))).thenReturn(Map.of(document, sheet));
        when(storage.inspect(sheet.key())).thenReturn(sheet.metadata());
        byte[] bytes = {1, 2, 3, 4};
        when(storage.open(sheet.key())).thenReturn(content(sheet.metadata(), bytes, new AtomicBoolean()));
        try (var original = service.citationOriginal(actor, document, generation)) {
            assertArrayEquals(bytes, original.inputStream().readAllBytes());
        }
    }

    @Test
    void aWorkbookOriginalIsReadAsSheetsAndAnythingElseIsNotReadableAsOne() throws Exception {
        byte[] xlsx = workbook();
        var book = new StoredObjectReference(new StoredObjectId(UUID.randomUUID()), new ObjectKey("raw/tenant/book"),
                "bao-cao.xlsx", new ObjectMetadata(xlsx.length, XLSX, new ContentSha256("b".repeat(64))));
        when(sources.originals(tenant, actor, Set.of(document))).thenReturn(Map.of(document, book));
        var closed = new AtomicBoolean();
        when(storage.open(book.key())).thenReturn(content(book.metadata(), xlsx, closed));

        assertEquals(List.of(new SpreadsheetPreview.Sheet("Doanh thu", "Hà Nội,3\n", false)),
                service.searchWorkbook(actor, document, generation));
        assertTrue(closed.get());
        verify(authorization, times(2)).require(actor, IamCapability.SEARCH_READ, false);

        // A Document of another type is not readable as a workbook, and its object does not stay open.
        var refused = new AtomicBoolean();
        when(sources.originals(tenant, actor, Set.of(document))).thenReturn(Map.of(document, reference));
        when(storage.open(reference.key())).thenReturn(content(reference.metadata(), PDF, refused));
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationWorkbook(actor, document, generation));
        assertTrue(refused.get());
    }

    /** One sheet with one row, enough to prove the bytes reached the reader unchanged. */
    private static byte[] workbook() throws Exception {
        try (var workbook = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            var row = workbook.createSheet("Doanh thu").createRow(0);
            row.createCell(0).setCellValue("Hà Nội");
            row.createCell(1).setCellValue(3);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void searchRequiresSearchReadBeforeAndAfterOpening() {
        when(storage.open(reference.key())).thenReturn(content(reference.metadata(), PDF, new AtomicBoolean()));
        service.searchOriginal(actor, document, generation, null).close();
        verify(authorization, times(2)).require(actor, IamCapability.SEARCH_READ, false);
    }

    @Test
    void rejectsChangedObjectsNonPdfBytesStaleGenerationsAndRevocationDuringOpen() {
        var closed = new AtomicBoolean();
        when(storage.open(reference.key())).thenReturn(content(reference(PDF.length + 1).metadata(), PDF, closed));
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationOriginal(actor, document, generation, null));
        assertTrue(closed.get());

        var html = "<html>".getBytes(StandardCharsets.US_ASCII);
        var htmlClosed = new AtomicBoolean();
        when(storage.open(reference.key())).thenReturn(content(reference.metadata(), html, htmlClosed));
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationOriginal(actor, document, generation, null));
        assertTrue(htmlClosed.get());

        var revokedClosed = new AtomicBoolean();
        when(storage.open(reference.key())).thenAnswer(_ -> {
            when(access.canRead(actor, new DocumentId(document))).thenReturn(false);
            return content(reference.metadata(), PDF, revokedClosed);
        });
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationOriginal(actor, document, generation, null));
        assertTrue(revokedClosed.get());

        when(access.canRead(actor, new DocumentId(document))).thenReturn(true);
        when(documents.isCurrent(eq(tenant), any(), eq(UUID.fromString(generation.toString())), anyString())).thenReturn(false);
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationOriginal(actor, document, generation, null));
    }

    @Test
    void rejectsMissingOrOversizedOriginalsBeforeOpeningStorage() {
        when(sources.originals(tenant, actor, Set.of(document))).thenReturn(Map.of());
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationOriginal(actor, document, generation, null));
        when(sources.originals(tenant, actor, Set.of(document)))
                .thenReturn(Map.of(document, reference(DocumentOriginalService.MAX_BYTES + 1)));
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citationOriginal(actor, document, generation, null));
        verify(storage, never()).open(any());
    }

    @Test
    void rangeIsClampedToTheObjectAndReadWithoutTheWholeObject() throws Exception {
        var closed = new AtomicBoolean();
        when(storage.openRange(reference.key(), 5, PDF.length - 1))
                .thenReturn(range(5, PDF.length - 1, PDF.length, closed));
        try (var pdf = service.searchOriginal(actor, document, generation, new ByteRange(5, Long.MAX_VALUE))) {
            assertEquals(new ByteRange(5, PDF.length - 1), pdf.range());
            assertArrayEquals(Arrays.copyOfRange(PDF, 5, PDF.length), pdf.inputStream().readAllBytes());
        }
        assertTrue(closed.get());
        verify(storage, never()).open(any());
        verify(authorization, times(2)).require(actor, IamCapability.SEARCH_READ, false);
    }

    @Test
    void firstRangeMustStartWithPdfMagicAndIsServedFromItsFirstByte() throws Exception {
        when(storage.openRange(reference.key(), 0, 2)).thenReturn(range(0, 2, PDF.length, new AtomicBoolean()));
        try (var pdf = service.citationOriginal(actor, document, generation, new ByteRange(0, 2))) {
            assertArrayEquals(Arrays.copyOfRange(PDF, 0, 3), pdf.inputStream().readAllBytes());
        }

        var html = "<html>%fixture...".getBytes(StandardCharsets.US_ASCII);
        var closed = new AtomicBoolean();
        when(storage.openRange(reference.key(), 0, 9)).thenReturn(new FakeRange(0, 9, PDF.length, html, closed));
        assertThrows(SearchDocumentUnavailableException.class,
                () -> service.citationOriginal(actor, document, generation, new ByteRange(0, 9)));
        assertTrue(closed.get());
    }

    @Test
    void rangeRejectsAChangedObjectOrAProviderRangeThatDiffers() {
        when(storage.inspect(reference.key())).thenReturn(reference(PDF.length + 1).metadata());
        assertThrows(SearchDocumentUnavailableException.class,
                () -> service.citationOriginal(actor, document, generation, new ByteRange(1, 4)));
        verify(storage, never()).openRange(any(), anyLong(), anyLong());

        when(storage.inspect(reference.key())).thenReturn(reference.metadata());
        var closed = new AtomicBoolean();
        when(storage.openRange(reference.key(), 1, 4)).thenReturn(range(1, 4, PDF.length + 7, closed));
        assertThrows(SearchDocumentUnavailableException.class,
                () -> service.citationOriginal(actor, document, generation, new ByteRange(1, 4)));
        assertTrue(closed.get());
    }

    @Test
    void everyRangeRechecksAuthoritySoRevocationStopsTheNextRange() throws Exception {
        when(storage.openRange(eq(reference.key()), anyLong(), anyLong()))
                .thenAnswer(call -> range(call.getArgument(1), call.getArgument(2), PDF.length, new AtomicBoolean()));
        service.citationOriginal(actor, document, generation, new ByteRange(0, 4)).close();

        when(access.canRead(actor, new DocumentId(document))).thenReturn(false);
        assertThrows(SearchDocumentUnavailableException.class,
                () -> service.citationOriginal(actor, document, generation, new ByteRange(5, 9)));
        verify(storage, times(1)).openRange(eq(reference.key()), anyLong(), anyLong());
    }

    @Test
    void rangeBeyondTheEndIsUnsatisfiableOnlyForAReader() {
        var unsatisfiable = assertThrows(DocumentOriginalService.RangeNotSatisfiableException.class,
                () -> service.searchOriginal(actor, document, generation, new ByteRange(PDF.length, Long.MAX_VALUE)));
        assertEquals(PDF.length, unsatisfiable.sizeBytes());
        verify(storage, never()).inspect(any());

        when(access.canRead(actor, new DocumentId(document))).thenReturn(false);
        assertThrows(SearchDocumentUnavailableException.class,
                () -> service.searchOriginal(actor, document, generation, new ByteRange(PDF.length, Long.MAX_VALUE)));
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

    private static ObjectRangeContent range(long first, long last, long total, AtomicBoolean closed) {
        return new FakeRange(first, last, total, Arrays.copyOfRange(PDF, (int) first, (int) last + 1), closed);
    }

    private record FakeRange(long first, long last, long totalBytes, byte[] bytes, AtomicBoolean closed, InputStream input)
            implements ObjectRangeContent {
        FakeRange(long first, long last, long totalBytes, byte[] bytes, AtomicBoolean closed) {
            this(first, last, totalBytes, bytes, closed, new ByteArrayInputStream(bytes));
        }

        @Override public InputStream inputStream() { return input; }
        @Override public void close() { closed.set(true); }
    }
}
