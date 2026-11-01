package io.memoryos.ingestion.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.connector.SourceSnapshotReader;
import io.memoryos.document.DocumentContent;
import io.memoryos.shared.TenantId;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** A provider's snapshot is read by the reader its integration registered for that input format. */
class SourceContentExtractorRouterTest {
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private final DoclingSourceContentExtractor docling = mock(DoclingSourceContentExtractor.class);

    @Test
    void everyProviderFormatGoesToItsOwnReader() throws Exception {
        var readers = new ArrayList<Reader>();
        for (SourceInputFormat format : SourceInputFormat.values())
            if (format != SourceInputFormat.BINARY) readers.add(new Reader(format));
        var router = router(readers).requireEveryFormat();

        for (Reader reader : readers) {
            var input = new SourceInputDescriptor(reader.format, "file-1", "1", null);
            assertSame(reader.answer, router.extract(TENANT, new ByteArrayInputStream(new byte[] {1}), 1, "a.json", input),
                    reader.format.name());
            assertEquals(1, reader.calls, reader.format.name());
        }
        verifyNoInteractions(docling);
    }

    @Test
    void aFormatWithoutAReaderFailsTheStartAndTwoReadersForOneFormatAreRefused() {
        var missing = assertThrows(IllegalStateException.class,
                () -> router(List.of(new Reader(SourceInputFormat.GOOGLE_DOCS))).requireEveryFormat());
        assertEquals("No snapshot reader for GOOGLE_SHEETS", missing.getMessage());

        var twice = assertThrows(IllegalStateException.class, () -> router(List.of(
                new Reader(SourceInputFormat.LARK_DOCX), new Reader(SourceInputFormat.LARK_DOCX))));
        assertEquals("Exactly one snapshot reader may read LARK_DOCX", twice.getMessage());
        // Uploaded and downloaded files are extraction's own; no integration may claim them.
        assertThrows(IllegalStateException.class, () -> router(List.of(new Reader(SourceInputFormat.BINARY))));
    }

    private SourceContentExtractorRouter router(List<? extends SourceSnapshotReader> readers) {
        return new SourceContentExtractorRouter(docling, _ -> null, new ObjectMapper(), List.copyOf(readers));
    }

    private static final class Reader implements SourceSnapshotReader {
        private final SourceInputFormat format;
        private final DocumentContent answer;
        private int calls;

        Reader(SourceInputFormat format) {
            this.format = format;
            this.answer = new DocumentContent("text/plain", format.name(), "", Map.of());
        }

        @Override public SourceInputFormat format() {
            return format;
        }

        @Override public DocumentContent extract(InputStream content, long size, String filename,
                SourceInputDescriptor input) {
            calls++;
            return answer;
        }
    }
}
