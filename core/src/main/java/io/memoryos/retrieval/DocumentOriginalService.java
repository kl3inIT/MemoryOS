package io.memoryos.retrieval;

import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Streams the stored original PDF of a readable Document so a reader can show the cited page and region.
 * Authority matches the passage readers: Search needs {@code SEARCH_READ}, Chat citations need membership
 * and Document eligibility. Every check is repeated after the object is opened, for each byte range too,
 * so a reader loses access between two ranges of the same document.
 */
@Service
public class DocumentOriginalService {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

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

    /** {@code range} is the served range clamped to the object, or null for the whole object. */
    public record OriginalPdf(StoredObjectReference reference, @Nullable ByteRange range, InputStream inputStream, Runnable closer)
            implements AutoCloseable {
        @Override public void close() { closer.run(); }
    }

    /** Search reader. */
    public OriginalPdf searchPdf(ActorId actor, UUID id, UUID generation, @Nullable ByteRange range) {
        return open(actor, IamCapability.SEARCH_READ, id, generation, range);
    }

    /** Chat citation reader. */
    public OriginalPdf citationPdf(ActorId actor, UUID id, UUID generation, @Nullable ByteRange range) {
        return open(actor, null, id, generation, range);
    }

    private OriginalPdf open(ActorId actor, @Nullable IamCapability capability, UUID id, UUID generation, @Nullable ByteRange requested) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SearchDocumentUnavailableException::new);
        if (capability != null) authorization.require(actor, capability, false);
        requireReadable(actor, tenant, id, generation);
        var reference = sources.originalPdf(tenant, actor, id)
                .filter(candidate -> candidate.metadata().sizeBytes() <= MAX_BYTES)
                .orElseThrow(SearchDocumentUnavailableException::new);
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
            var input = opened.range() == null || opened.range().first() == 0
                    ? startingWithPdfMagic(opened.input(), opened.range() == null ? PDF_MAGIC.length : opened.range().length())
                    : opened.input();
            if (capability != null) authorization.require(actor, capability, false);
            requireReadable(actor, tenant, id, generation);
            if (sources.originalPdf(tenant, actor, id).filter(reference::equals).isEmpty()) throw new SearchDocumentUnavailableException();
            return new OriginalPdf(reference, opened.range(), input, opened.closer());
        } catch (IOException failed) {
            opened.closer().run();
            throw new SearchDocumentUnavailableException();
        } catch (RuntimeException failed) {
            opened.closer().run();
            throw failed;
        }
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
