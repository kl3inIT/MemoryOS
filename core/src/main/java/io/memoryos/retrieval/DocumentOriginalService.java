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
import io.memoryos.objectstorage.ObjectContent;
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
 * and Document eligibility. Every check is repeated after the object is opened.
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

    public record OriginalPdf(StoredObjectReference reference, InputStream inputStream, ObjectContent content) implements AutoCloseable {
        @Override public void close() { content.close(); }
    }

    /** Search reader. */
    public OriginalPdf searchPdf(ActorId actor, UUID id, UUID generation) {
        return open(actor, IamCapability.SEARCH_READ, id, generation);
    }

    /** Chat citation reader. */
    public OriginalPdf citationPdf(ActorId actor, UUID id, UUID generation) {
        return open(actor, null, id, generation);
    }

    private OriginalPdf open(ActorId actor, @Nullable IamCapability capability, UUID id, UUID generation) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SearchDocumentUnavailableException::new);
        if (capability != null) authorization.require(actor, capability, false);
        requireReadable(actor, tenant, id, generation);
        var reference = sources.originalPdf(tenant, actor, id)
                .filter(candidate -> candidate.metadata().sizeBytes() <= MAX_BYTES)
                .orElseThrow(SearchDocumentUnavailableException::new);
        var content = storage.open(reference.key());
        try {
            if (!content.metadata().equals(reference.metadata())) throw new SearchDocumentUnavailableException();
            var input = new BufferedInputStream(content.inputStream(), 8192);
            input.mark(PDF_MAGIC.length);
            if (!Arrays.equals(input.readNBytes(PDF_MAGIC.length), PDF_MAGIC)) throw new SearchDocumentUnavailableException();
            input.reset();
            if (capability != null) authorization.require(actor, capability, false);
            requireReadable(actor, tenant, id, generation);
            if (sources.originalPdf(tenant, actor, id).filter(reference::equals).isEmpty()) throw new SearchDocumentUnavailableException();
            return new OriginalPdf(reference, input, content);
        } catch (IOException failed) {
            content.close();
            throw new SearchDocumentUnavailableException();
        } catch (RuntimeException failed) {
            content.close();
            throw failed;
        }
    }

    private void requireReadable(ActorId actor, TenantId tenant, UUID id, UUID generation) {
        var document = new DocumentId(id);
        if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                || !access.canRead(actor, document)
                || !documents.isCurrent(tenant, document, generation, search.identity()))
            throw new SearchDocumentUnavailableException();
    }
}
