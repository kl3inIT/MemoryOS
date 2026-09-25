package io.memoryos.retrieval;

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
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Streams the stored original of a readable Document, whatever its media type, so a reader can show the file as
 * it looks rather than its extraction, and so run_python can stage the file behind a Chat search hit.
 * An original the stored object declares as a PDF still proves it is one before it is served, because pdf.js
 * parses it.
 * Authority matches the passage readers: Search needs {@code SEARCH_READ}, Chat citations need membership
 * and Document eligibility. Every check is repeated after the object is opened, for each byte range too,
 * so a reader loses access between two ranges of the same document.
 */
@Service
public class DocumentOriginalService {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final String WORKBOOK = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final SourceDocumentAccessResolver access;
    private final DocumentChunkPort documents;
    private final OpenSearchIndexService search;
    private final SourceSearchService sources;
    private final ObjectStorage storage;

    public DocumentOriginalService(TenantAccessResolver tenants, IamAuthorization authorization, SourceDocumentAccessResolver access,
            DocumentChunkPort documents, OpenSearchIndexService search, SourceSearchService sources, ObjectStorage storage) {
        this.tenants = tenants; this.authorization = authorization; this.access = access;
        this.documents = documents; this.search = search; this.sources = sources; this.storage = storage;
    }

    /** Inclusive byte positions; an open-ended request uses {@link Long#MAX_VALUE} as {@code last}. */
    public record ByteRange(long first, long last) {
        public ByteRange {
            if (first < 0 || last < first) throw new IllegalArgumentException("byte range must be non-negative and ordered");
        }

        public long length() { return last - first + 1; }
    }

    /** The requested range starts at or beyond the end of an original the actor may read. */
    public static final class RangeNotSatisfiableException extends RuntimeException {
        private final long sizeBytes;

        public RangeNotSatisfiableException(long sizeBytes) {
            super("Requested range is outside the original", null, false, false);
            this.sizeBytes = sizeBytes;
        }

        public long sizeBytes() { return sizeBytes; }
    }

    /** An authorized original of any media type; {@code range} is the served range clamped to the object, or null for the whole object. */
    public record Original(StoredObjectReference reference, @Nullable ByteRange range, InputStream inputStream, Runnable closer)
            implements AutoCloseable {
        @Override public void close() { closer.run(); }
    }

    /**
     * Stored originals of any media type for Documents a Chat actor may cite, keyed by Document id, so run_python can
     * stage the file behind a search hit as Onyx does. Unreadable, ineligible and oversized originals are absent.
     */
    public java.util.Map<UUID, StoredObjectReference> citationOriginals(ActorId actor, java.util.Collection<UUID> ids) {
        var tenant = tenants.findActiveTenant(actor).orElse(null);
        if (tenant == null || ids.isEmpty()) return java.util.Map.of();
        var readable = access.readableDocuments(actor, List.copyOf(ids));
        var result = new java.util.LinkedHashMap<UUID, StoredObjectReference>();
        sources.originals(tenant, actor, readable).forEach((id, reference) -> {
            if (reference.metadata().sizeBytes() <= MAX_BYTES) result.put(id, reference);
        });
        return java.util.Map.copyOf(result);
    }

    /** The whole original of any media type, with the Chat citation authority, rechecked after the object is opened. */
    public Original citationOriginal(ActorId actor, UUID id, UUID generation) {
        return citationOriginal(actor, id, generation, null);
    }

    /** Search reader for the stored original, whatever its media type. */
    public Original searchOriginal(ActorId actor, UUID id, UUID generation, @Nullable ByteRange range) {
        return open(actor, IamCapability.SEARCH_READ, id, generation, range);
    }

    /** Chat citation reader for the stored original, whatever its media type. */
    public Original citationOriginal(ActorId actor, UUID id, UUID generation, @Nullable ByteRange range) {
        return open(actor, null, id, generation, range);
    }

    /**
     * The sheets of a workbook original, read under Search authority. A workbook is served as text per sheet
     * rather than as bytes, because the app ships no client-side workbook parser.
     */
    public java.util.List<SpreadsheetPreview.Sheet> searchWorkbook(ActorId actor, UUID id, UUID generation) {
        return sheets(searchOriginal(actor, id, generation, null));
    }

    /** The same sheets under the Chat citation authority. */
    public java.util.List<SpreadsheetPreview.Sheet> citationWorkbook(ActorId actor, UUID id, UUID generation) {
        return sheets(citationOriginal(actor, id, generation, null));
    }

    /** Reads an authorized original as a workbook, and closes the object whether or not it is one. */
    private static java.util.List<SpreadsheetPreview.Sheet> sheets(Original original) {
        try (var open = original) {
            if (!WORKBOOK.equals(baseType(open.reference()))) throw new SearchDocumentUnavailableException();
            return SpreadsheetPreview.parse(open.inputStream());
        } catch (IOException unreadable) {
            throw new SearchDocumentUnavailableException();
        }
    }

    private Original open(ActorId actor, @Nullable IamCapability capability, UUID id, UUID generation,
                          @Nullable ByteRange requested) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SearchDocumentUnavailableException::new);
        if (capability != null) authorization.require(actor, capability, false);
        requireReadable(actor, tenant, id, generation);
        var reference = lookup(tenant, actor, id)
                .filter(candidate -> candidate.metadata().sizeBytes() <= MAX_BYTES)
                .orElseThrow(SearchDocumentUnavailableException::new);
        // An original the object declares as a PDF is the one pdf.js will parse, so it still proves it is one.
        boolean pdf = declaresPdf(reference);
        Opened opened;
        if (requested == null) {
            var content = storage.open(reference.key());
            opened = new Opened(null, content.inputStream(), content::close);
            if (!content.metadata().equals(reference.metadata())) opened.fail();
        } else {
            long size = reference.metadata().sizeBytes();
            if (requested.first() >= size) throw new RangeNotSatisfiableException(size);
            var range = new ByteRange(requested.first(), Math.min(requested.last(), size - 1));
            // A ranged read carries no whole-object checksum, so the immutable object's metadata is checked first.
            if (!storage.inspect(reference.key()).equals(reference.metadata())) throw new SearchDocumentUnavailableException();
            var content = storage.openRange(reference.key(), range.first(), range.last());
            opened = new Opened(range, content.inputStream(), content::close);
            if (content.first() != range.first() || content.last() != range.last() || content.totalBytes() != size) opened.fail();
        }
        try {
            var input = pdf && (opened.range() == null || opened.range().first() == 0)
                    ? startingWithPdfMagic(opened.input(), opened.range() == null ? PDF_MAGIC.length : opened.range().length())
                    : opened.input();
            if (capability != null) authorization.require(actor, capability, false);
            requireReadable(actor, tenant, id, generation);
            if (lookup(tenant, actor, id).filter(reference::equals).isEmpty()) throw new SearchDocumentUnavailableException();
            return new Original(reference, opened.range(), input, opened.closer());
        } catch (IOException failed) {
            opened.closer().run();
            throw new SearchDocumentUnavailableException();
        } catch (RuntimeException failed) {
            opened.closer().run();
            throw failed;
        }
    }

    private java.util.Optional<StoredObjectReference> lookup(TenantId tenant, ActorId actor, UUID id) {
        return java.util.Optional.ofNullable(sources.originals(tenant, actor, java.util.Set.of(id)).get(id));
    }

    private static boolean declaresPdf(StoredObjectReference reference) {
        return "application/pdf".equals(baseType(reference));
    }

    /** The declared media type without its parameters, lowercased, as the checks above compare it. */
    private static String baseType(StoredObjectReference reference) {
        var declared = reference.metadata().mediaType();
        int parameters = declared.indexOf(';');
        return (parameters < 0 ? declared : declared.substring(0, parameters)).strip().toLowerCase(java.util.Locale.ROOT);
    }

    private record Opened(@Nullable ByteRange range, InputStream input, Runnable closer) {
        void fail() {
            closer.run();
            throw new SearchDocumentUnavailableException();
        }
    }

    /** Checks the leading bytes that the stream covers, then rewinds so they are still served. */
    private static InputStream startingWithPdfMagic(InputStream stream, long available) throws IOException {
        int length = (int) Math.min(PDF_MAGIC.length, available);
        var input = new BufferedInputStream(stream, 8192);
        input.mark(length);
        if (!Arrays.equals(input.readNBytes(length), Arrays.copyOf(PDF_MAGIC, length))) throw new SearchDocumentUnavailableException();
        input.reset();
        return input;
    }

    private void requireReadable(ActorId actor, TenantId tenant, UUID id, UUID generation) {
        var document = new DocumentId(id);
        if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                || !access.canRead(actor, document)
                || !documents.isCurrent(tenant, document, generation, search.identity()))
            throw new SearchDocumentUnavailableException();
    }
}
