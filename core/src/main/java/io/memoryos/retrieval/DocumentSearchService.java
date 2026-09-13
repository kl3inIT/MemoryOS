package io.memoryos.retrieval;

import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DocumentSearchService {
    private static final Comparator<SearchHit> HIT_ORDER = Comparator.comparingDouble(SearchHit::score).reversed()
            .thenComparing(SearchHit::documentId).thenComparingInt(SearchHit::ordinal);

    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final SourceDocumentAccessResolver access;
    private final DocumentChunkPort documents;
    private final OpenSearchIndexService search;
    private final MeterRegistry metrics;
    private final SourceSearchService sourceSearch;
    private final SearchTimings timings;

    public DocumentSearchService(TenantAccessResolver tenants, IamAuthorization authorization, SourceDocumentAccessResolver access,
            DocumentChunkPort documents, OpenSearchIndexService search, MeterRegistry metrics, SourceSearchService sourceSearch, SearchTimings timings) {
        this.tenants = tenants; this.authorization = authorization; this.access = access;
        this.documents = documents; this.search = search; this.metrics = metrics;
        this.sourceSearch = sourceSearch;
        this.timings = timings;
    }

    public SearchPage search(ActorId actor, SearchRequest request) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SearchDocumentUnavailableException::new);
        authorization.require(actor, IamCapability.SEARCH_READ, false);
        long started = System.nanoTime();
        String outcome = "failed";
        try {
            var hits = authorized(actor, tenant, search.search(tenant, request.query(), request.mediaTypes(), request.updatedSince()));
            requireSearchAccess(actor, tenant);
            var grouped = new LinkedHashMap<UUID, List<SearchHit>>();
            hits.stream()
                    .sorted(HIT_ORDER)
                    .forEach(h -> grouped.computeIfAbsent(h.documentId(), _ -> new ArrayList<>()).add(h));
            var all = grouped.values().stream().map(group -> {
                var best = group.getFirst();
                return new SearchPage.Result(best.documentId(), best.generation(), best.title(), best.mediaType(),
                        best.updatedAt(), best.score(), mergeSections(group));
            }).toList();
            int start = Math.min(request.page() * request.pageSize(), all.size());
            int end = Math.min(start + request.pageSize(), all.size());
            outcome = "success";
            return new SearchPage(all.subList(start, end), request.page(), end < all.size(), search.candidateLimit());
        } finally {
            metrics.timer("memoryos.search.query.duration", "outcome", outcome)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /** Caller supplies an owner-authorized file-to-document scope; never widens to organization Search. */
    public Set<UUID> readyFiles(ActorId actor, TenantId expectedTenant, Map<UUID, UUID> files) {
        if (tenants.findActiveTenant(actor).filter(expectedTenant::equals).isEmpty()) throw new SearchDocumentUnavailableException();
        return fileGenerations(expectedTenant, files).keySet();
    }

    private Map<UUID, UUID> fileGenerations(TenantId tenant, Map<UUID, UUID> files) {
        if (files.size() > 4020) throw new SearchRequestException();
        var ids = files.values().stream().distinct().toList();
        var result = new HashMap<UUID, UUID>();
        for (int offset = 0; offset < ids.size(); offset += 1000)
            result.putAll(documents.currentGenerations(tenant, ids.subList(offset, Math.min(offset + 1000, ids.size())), search.identity()));
        return Map.copyOf(result);
    }

    public List<SearchHit> searchFiles(ActorId actor, TenantId expectedTenant, Map<UUID, UUID> files, String query) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SearchDocumentUnavailableException::new);
        if (!tenant.equals(expectedTenant)) throw new SearchDocumentUnavailableException();
        if (query == null || query.isBlank() || query.length() > 2000 || files.size() > 4020) throw new SearchRequestException();
        if (files.isEmpty()) return List.of();
        var generations = fileGenerations(tenant, files);
        var hits = search.searchFiles(tenant, query, generations, files);
        var current = fileGenerations(tenant, files);
        return hits.stream().filter(hit -> hit.generation().equals(current.get(hit.documentId()))).limit(20).toList();
    }

    /** Private reader: the caller supplies freshly owner-authorized file mappings. */
    public SearchDocument fileDocument(ActorId actor, TenantId tenant, Map<UUID, UUID> files, UUID document, UUID generation, int from) {
        if (from < 0 || from > 9999) throw new SearchRequestException();
        if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                || !generation.equals(fileGenerations(tenant, files).get(document))) throw new SearchDocumentUnavailableException();
        var result = search.document(tenant, document, generation, from, 20);
        if (!generation.equals(fileGenerations(tenant, files).get(document))) throw new SearchDocumentUnavailableException();
        return result;
    }

    private static List<SearchPage.Section> mergeSections(List<SearchHit> rankedHits) {
        // Callers have already filtered current generations and grouped hits by document.
        // Keep the best occurrence if an indexed chunk appears more than once.
        var ordered = new TreeMap<Integer, SearchHit>();
        rankedHits.forEach(hit -> ordered.putIfAbsent(hit.ordinal(), hit));
        var sections = new ArrayList<SearchPage.Section>();
        var adjacent = new ArrayList<SearchHit>();
        for (var hit : ordered.values()) {
            if (!adjacent.isEmpty() && hit.ordinal() != adjacent.getLast().ordinal() + 1) {
                sections.add(section(adjacent));
                adjacent.clear();
            }
            adjacent.add(hit);
        }
        if (!adjacent.isEmpty()) sections.add(section(adjacent));
        return sections.stream()
                .sorted(Comparator.comparingDouble(SearchPage.Section::score).reversed()
                        .thenComparingInt(SearchPage.Section::matchingOrdinal))
                .limit(3).toList();
    }

    private static SearchPage.Section section(List<SearchHit> chunks) {
        var best = chunks.stream().min(HIT_ORDER).orElseThrow();
        return new SearchPage.Section(chunks.getFirst().ordinal(), chunks.getLast().ordinal(), best.ordinal(),
                best.score(), chunks.stream().map(SearchHit::content).collect(Collectors.joining("\n")),
                chunks.stream().map(hit -> new SearchPage.ChunkProvenance(hit.ordinal(), hit.provenanceJson())).toList());
    }

    public SearchDocument document(ActorId actor, UUID id, UUID generation, int from) {
        if (from < 0 || from > 9999) throw new SearchRequestException();
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SearchDocumentUnavailableException::new);
        authorization.require(actor, IamCapability.SEARCH_READ, false);
        requireDocumentAccess(actor, tenant, id, generation);
        var result = search.document(tenant, id, generation, from, 20);
        requireSearchAccess(actor, tenant);
        requireDocumentAccess(actor, tenant, id, generation);
        return result;
    }

    private void requireSearchAccess(ActorId actor, TenantId tenant) {
        if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()) throw new SearchDocumentUnavailableException();
        authorization.require(actor, IamCapability.SEARCH_READ, false);
    }

    private void requireDocumentAccess(ActorId actor, TenantId tenant, UUID id, UUID generation) {
        var document = new DocumentId(id);
        if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                || !access.canRead(actor, document)
                || !documents.isCurrent(tenant, document, generation, search.identity()))
            throw new SearchDocumentUnavailableException();
    }

    private List<SearchHit> authorized(ActorId actor, TenantId tenant, List<SearchHit> hits) {
        var ids = hits.stream().map(SearchHit::documentId).distinct().toList();
        var current = documents.currentGenerations(tenant, ids, search.identity());
        var eligible = access.readableDocuments(actor, ids);
        return hits.stream().filter(h -> h.generation().equals(current.get(h.documentId()))
                && eligible.contains(h.documentId())).sorted(HIT_ORDER).toList();
    }

    /** Rank authorization-filtered chunks from bounded queries; no LLM is used by Retrieval. */
    public SourceSearchScope scope(ActorId actor) { return timings.measure(SearchTimings.Stage.PREFETCH, () -> sourceSearch.scope(actor)); }

    public SearchResults ranked(SourceSearchScope scope, List<SearchQuery> queries, SearchFilters filters, Runnable checkActive) {
        if (queries.isEmpty() || queries.size() > 8) throw new SearchRequestException();
        var tenant = scope.tenant();
        if (tenants.findActiveTenant(scope.actor()).filter(tenant::equals).isEmpty()) throw new SearchDocumentUnavailableException();
        if (scope.sources().isEmpty()) return new SearchResults(scope, List.of());
        var batches = search.batch(scope, queries, filters, checkActive);
        checkActive.run();
        if (tenants.findActiveTenant(scope.actor()).filter(tenant::equals).isEmpty()) throw new SearchDocumentUnavailableException();
        var ids = batches.stream().flatMap(List::stream).map(SearchHit::documentId).distinct().toList();
        var current = new HashMap<UUID, UUID>();
        var metadata = new HashMap<UUID, List<DocumentSourceMetadata>>();
        for (int offset = 0; offset < ids.size(); offset += 1000) {
            checkActive.run();
            var batch = ids.subList(offset, Math.min(offset + 1000, ids.size()));
            current.putAll(timings.measure(SearchTimings.Stage.AUTHORIZATION, () -> documents.currentGenerations(tenant, batch, search.identity())));
            timings.measure(SearchTimings.Stage.AUTHORIZATION, () -> sourceSearch.readableMetadata(scope, batch)).forEach((id, origins) ->
                    metadata.put(id, origins.stream().filter(filters::matches).toList()));
        }
        var representatives = new LinkedHashMap<String, SearchHit>();
        long fusionStarted = System.nanoTime();
        var scores = new HashMap<String, Double>();
        var firstRank = new HashMap<String, Integer>();
        var firstQuery = new HashMap<String, Integer>();
        for (int queryIndex = 0; queryIndex < queries.size(); queryIndex++) {
            var query = queries.get(queryIndex);
            checkActive.run();
            var hits = batches.get(queryIndex).stream().filter(h -> h.generation().equals(current.get(h.documentId()))
                    && !metadata.getOrDefault(h.documentId(), List.of()).isEmpty())
                    .map(h -> h.withOrigins(metadata.get(h.documentId()))).toList();
            var seen = new HashSet<String>();
            int rank = 0;
            for (var hit : hits) {
                String key = hit.documentId() + ":" + hit.generation() + ":" + hit.ordinal();
                if (!seen.add(key)) continue;
                representatives.putIfAbsent(key, hit);
                scores.merge(key, query.weight() / (50 + ++rank), Double::sum);
                firstRank.putIfAbsent(key, rank);
                firstQuery.putIfAbsent(key, queryIndex);
            }
        }
        var ranked = representatives.entrySet().stream().sorted(Comparator
                .<java.util.Map.Entry<String, SearchHit>>comparingDouble(e -> scores.get(e.getKey())).reversed()
                .thenComparingInt(e -> firstRank.get(e.getKey())).thenComparingInt(e -> firstQuery.get(e.getKey())))
                .map(entry -> {
            var hit = entry.getValue();
            return hit.withScore(scores.get(entry.getKey()));
        }).toList();
        metrics.timer("memoryos.search.stage.duration", "stage", "fusion", "outcome", "success")
                .record(System.nanoTime() - fusionStarted, TimeUnit.NANOSECONDS);
        return new SearchResults(scope, ranked);
    }

    /** The result retains its actor and narrowed source scope; expansion rechecks current resource authority. */
    public List<SearchPage.Passage> expand(SearchResults results, SearchSection section, int neighbors) {
        if (!results.contains(section) || neighbors < 0 || neighbors > 5) throw new SearchRequestException();
        var hit = section.anchor();
        requireExpansionAccess(results.scope(), hit);
        var passages = new TreeMap<Integer, SearchPage.Passage>();
        section.passages().forEach(p -> passages.put(p.ordinal(), p));
        if (neighbors > 0) {
            int start = Math.max(0, section.start() - neighbors);
            if (start < section.start()) {
                search.document(results.scope().tenant(), hit.documentId(), hit.generation(), start,
                        section.start() - start).passages().forEach(p -> passages.put(p.ordinal(), p));
                requireExpansionAccess(results.scope(), hit);
            }
            if (section.end() < 9999) {
                search.document(results.scope().tenant(), hit.documentId(), hit.generation(), section.end() + 1, neighbors)
                        .passages().forEach(p -> passages.put(p.ordinal(), p));
            }
        }
        requireExpansionAccess(results.scope(), hit);
        return List.copyOf(passages.values());
    }

    private void requireExpansionAccess(SourceSearchScope scope, SearchHit hit) {
        if (tenants.findActiveTenant(scope.actor()).filter(scope.tenant()::equals).isEmpty()
                || !documents.isCurrent(scope.tenant(), new DocumentId(hit.documentId()), hit.generation(), search.identity()))
            throw new SearchDocumentUnavailableException();
        var origins = sourceSearch.readableMetadata(scope, List.of(hit.documentId()))
                .getOrDefault(hit.documentId(), List.of());
        // A still-public mapping must not authorize returning metadata from a now-private origin.
        if (origins.isEmpty() || !origins.containsAll(hit.origins())) throw new SearchDocumentUnavailableException();
    }
}
