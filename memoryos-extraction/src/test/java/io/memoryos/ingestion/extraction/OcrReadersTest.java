package io.memoryos.ingestion.extraction;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrPageReader;
import io.memoryos.ai.ocr.OcrProvider;
import io.memoryos.ai.ocr.OcrServiceUsage;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** MEM-243: each document reads with the reader the Tenant chose at that moment, without a restart. */
class OcrReadersTest {
    private final OcrConnectionService connections = mock(OcrConnectionService.class);
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID id = UUID.randomUUID();
    private final OcrReaders readers = new OcrReaders(connections, OcrAdapterRegistry.installed(
            new PaddleOcrVlProperties(Duration.ofSeconds(10), 200, null, null), mock(OcrPageReader.class), mock(OcrServiceUsage.class), new ObjectMapper()));

    @Test
    void withoutAChosenReaderDoclingReadsScans() {
        assertNull(readers.forTenant(tenant));
    }

    @Test
    void aReaderIsKeptWhileItsConnectionIsUnchangedAndReplacedWhenItChanges() {
        try (readers) {
            when(connections.reader(tenant)).thenReturn(connection(1));
            when(connections.key(connection(1))).thenReturn("");
            var first = readers.forTenant(tenant);
            assertInstanceOf(PaddleOcrVlExtractor.class, first);
            assertSame(first, readers.forTenant(tenant));

            when(connections.reader(tenant)).thenReturn(connection(2));
            assertNotSame(first, readers.forTenant(tenant), "the saved change applies to the next document");

            when(connections.reader(tenant)).thenReturn(null);
            assertNull(readers.forTenant(tenant), "clearing the reader returns scans to Docling");
        }
    }

    private OcrConnectionService.Connection connection(long revision) {
        return new OcrConnectionService.Connection(id, tenant.value(), OcrProvider.PADDLEOCR_VL, "http://ocr.internal:8080",
                null, DataBoundary.INTERNAL, null, revision);
    }
}
