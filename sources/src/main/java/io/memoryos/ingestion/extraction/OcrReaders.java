package io.memoryos.ingestion.extraction;

import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.shared.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The reader each Tenant chose, asked for once a document, so a change made on the OCR tab applies to the next document
 * in this process without a restart. A connection's reader is kept while its revision stands; one a change replaced may
 * still be reading, so it is closed with this process rather than at once.
 */
final class OcrReaders implements AutoCloseable {
    private record Kept(long revision, OcrReader reader) {}

    private final OcrConnectionService connections;
    private final OcrAdapterRegistry adapters;
    private final Map<UUID, Kept> readers = new ConcurrentHashMap<>();
    private final List<OcrReader> replaced = new ArrayList<>();

    OcrReaders(OcrConnectionService connections, OcrAdapterRegistry adapters) {
        this.connections = connections;
        this.adapters = adapters;
    }

    /** The Tenant's reader, or null when Docling reads its scans with its own OCR. */
    @Nullable OcrReader forTenant(TenantId tenant) {
        var connection = connections.reader(tenant);
        if (connection == null || connection.id() == null) return null;
        return readers.compute(connection.id(), (id, kept) -> {
            if (kept != null && kept.revision() == connection.revision()) return kept;
            if (kept != null) synchronized (replaced) { replaced.add(kept.reader()); }
            return new Kept(connection.revision(),
                    adapters.adapter(connection.provider()).reader(connection, connections.key(connection)));
        }).reader();
    }

    @Override public void close() {
        readers.values().forEach(kept -> kept.reader().close());
        synchronized (replaced) { replaced.forEach(OcrReader::close); }
    }
}
