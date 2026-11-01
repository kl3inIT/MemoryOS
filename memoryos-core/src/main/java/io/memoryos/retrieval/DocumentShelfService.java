package io.memoryos.retrieval;

import io.memoryos.connector.ReadableSource;
import io.memoryos.connector.SourceDocumentBrowse;
import io.memoryos.connector.SourceDocumentEntry;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.Sha256;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Browses the Source documents a reader may open, without a search term. Authority is Search's: {@code SEARCH_READ},
 * then the connector's per-document read scope at every read, so a revoked Group, membership or provider grant
 * removes a document at the next page. Generations are reported only for the index Search currently serves, because
 * the reader previews through Search.
 */
@Service
public class DocumentShelfService {
    private static final int MAX_SOURCES = 500;
    private static final int MAX_QUERY = 200;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_IDS = 500;
    private static final int MAX_CURSOR = 2048;
    private static final Set<String> CATEGORIES = Set.of("DOCUMENT", "SPREADSHEET", "IMAGE", "PRESENTATION", "OTHER");

    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final SourceSearchService sources;
    private final OpenSearchIndexService search;

    public DocumentShelfService(TenantAccessResolver tenants, IamAuthorization authorization, SourceSearchService sources,
            OpenSearchIndexService search) {
        this.tenants = tenants;
        this.authorization = authorization;
        this.sources = sources;
        this.search = search;
    }

    /**
     * One page of readable documents. Requires {@code SEARCH_READ} and an active Tenant; the query is at most 200
     * characters, the limit 1 to 100, and a cursor must come from the same reader, filters and sort.
     */
    public ShelfPage page(ActorId actor, ShelfQuery query) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SearchDocumentUnavailableException::new);
        authorization.require(actor, IamCapability.SEARCH_READ, false);
        String text = query.query().strip();
        if (text.length() > MAX_QUERY || query.limit() < 1 || query.limit() > MAX_LIMIT
                || query.sourceIds().size() > MAX_SOURCES || !CATEGORIES.containsAll(query.categories()))
            throw new SearchRequestException("Enter a name of at most 200 characters and valid document filters.",
                    "invalid document shelf request");
        String scope = scope(tenant, actor, text, query);
        var after = after(query.cursor(), scope);
        var found = sources.browse(tenant, actor, search.identity(), new SourceDocumentBrowse(text, query.sourceIds(),
                query.categories(), query.sort() == ShelfQuery.Sort.NAME, after, query.limit() + 1));
        boolean more = found.size() > query.limit();
        var page = more ? found.subList(0, query.limit()) : found;
        String next = null;
        if (more) {
            var last = page.getLast();
            next = encode(scope + last.documentId() + "|" + last.updatedAt() + "|" + last.filename());
        }
        return new ShelfPage(page.stream().map(DocumentShelfService::document).toList(), next);
    }

    /**
     * The readable documents among {@code ids} (at most 500), in any order. A reader without {@code SEARCH_READ} or an
     * active Tenant gets none, so callers can drop marked documents without handling a denial.
     */
    public List<ShelfDocument> find(ActorId actor, Collection<UUID> ids) {
        if (ids.size() > MAX_IDS) throw new IllegalArgumentException("document lookup exceeds " + MAX_IDS);
        if (ids.isEmpty()) return List.of();
        var tenant = tenants.findActiveTenant(actor).orElse(null);
        if (tenant == null || !authorization.effectiveCapabilities(actor).contains(IamCapability.SEARCH_READ)) return List.of();
        return sources.entries(tenant, actor, search.identity(), ids).stream().map(DocumentShelfService::document).toList();
    }

    /** The Sources the reader may search, and so filter the shelf by, up to 500. Requires {@code SEARCH_READ}. */
    public List<SourceSearchService.SourceOption> sources(ActorId actor) {
        tenants.findActiveTenant(actor).orElseThrow(SearchDocumentUnavailableException::new);
        authorization.require(actor, IamCapability.SEARCH_READ, false);
        var result = new ArrayList<SourceSearchService.SourceOption>();
        for (int offset = 0; offset < MAX_SOURCES; offset += MAX_LIMIT) {
            var options = sources.options(actor, offset, MAX_LIMIT);
            result.addAll(options);
            if (options.size() < MAX_LIMIT) break;
        }
        return List.copyOf(result);
    }

    /**
     * Every Source the reader may read from, whatever its state except DELETING, by name, up to 500, with the
     * Documents the reader may open there. Requires {@code SEARCH_READ}, as the documents themselves do.
     */
    public List<ShelfSource> catalog(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SearchDocumentUnavailableException::new);
        authorization.require(actor, IamCapability.SEARCH_READ, false);
        return sources.readableSources(tenant, actor, MAX_SOURCES).stream().map(DocumentShelfService::source).toList();
    }

    private static ShelfSource source(ReadableSource source) {
        var status = switch (source.status()) {
            case NOT_STARTED -> ShelfSource.Status.NOT_STARTED;
            case INDEXING -> ShelfSource.Status.INDEXING;
            case ACTIVE -> ShelfSource.Status.ACTIVE;
            case PAUSED, PAUSING -> ShelfSource.Status.PAUSED;
            case FAILED -> ShelfSource.Status.FAILED;
            case DELETING -> throw new IllegalStateException("a deleting Source is never readable");
        };
        return new ShelfSource(source.id(), source.name(), source.type(), source.access(), status,
                source.readableDocuments(), source.lastSucceededAt(), source.groups(), source.managerName());
    }

    private static ShelfDocument document(SourceDocumentEntry entry) {
        var access = switch (entry.access()) {
            case PUBLIC -> ShelfDocument.Access.PUBLIC;
            case PRIVATE -> ShelfDocument.Access.GROUP;
            case SYNC -> ShelfDocument.Access.PROVIDER;
        };
        return new ShelfDocument(entry.documentId(), entry.generation(), entry.filename(), entry.title(), entry.mediaType(),
                entry.sizeBytes(), entry.category(), entry.updatedAt(), entry.sourceId(), entry.sourceName(),
                entry.sourceType(), entry.providerUrl(), access, entry.groups());
    }

    /** Binds a cursor to the Tenant, reader, sort and filters, so it cannot resume a different listing. */
    private static String scope(TenantId tenant, ActorId actor, String text, ShelfQuery query) {
        String filters = text + "\n" + query.sourceIds().stream().map(UUID::toString).sorted().collect(Collectors.joining(","))
                + "\n" + query.categories().stream().sorted().collect(Collectors.joining(","));
        return tenant.value() + "|" + actor.value() + "|" + query.sort() + "|" + Sha256.hex(filters) + "|";
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static SourceDocumentBrowse.@Nullable After after(@Nullable String cursor, String scope) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > MAX_CURSOR) throw new IllegalArgumentException();
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith(scope)) throw new IllegalArgumentException();
            String[] fields = decoded.substring(scope.length()).split("\\|", 3);
            if (fields.length != 3 || fields[2].isEmpty()) throw new IllegalArgumentException();
            return new SourceDocumentBrowse.After(Instant.parse(fields[1]), fields[2], UUID.fromString(fields[0]));
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new SearchRequestException("The document list cursor is invalid. Reload the list.",
                    "invalid or mismatched document shelf cursor");
        }
    }
}
