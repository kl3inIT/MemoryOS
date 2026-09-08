package io.memoryos.retrieval.application;

import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.retrieval.SearchDocument;
import io.memoryos.retrieval.SearchDocumentUnavailableException;
import io.memoryos.retrieval.SearchHit;
import io.memoryos.retrieval.SearchPage;
import io.memoryos.retrieval.SearchRequest;
import io.memoryos.retrieval.SearchRequestException;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final SourceDocumentAccessResolver access;
    private final DocumentChunkPort documents;
    private final OpenSearchIndexService search;
    private final MeterRegistry metrics;

    public DocumentSearchService(TenantAccessResolver tenants, SourceDocumentAccessResolver access,
            DocumentChunkPort documents, OpenSearchIndexService search, MeterRegistry metrics) {
        this.tenants = tenants; this.access = access; this.documents = documents; this.search = search; this.metrics = metrics;
    }

    public SearchPage search(ActorId actor, SearchRequest request) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SearchDocumentUnavailableException::new);
        long started = System.nanoTime();
        String outcome = "failed";
        try {
            var hits = search.search(tenant, request.query(), request.mediaTypes(), request.updatedSince());
            var ids = hits.stream().map(SearchHit::documentId).distinct().toList();
            var current = documents.currentGenerations(tenant, ids, search.identity());
            var eligible = access.readableDocuments(actor, ids);
            var grouped = new LinkedHashMap<UUID, List<SearchHit>>();
            hits.stream().filter(h -> h.generation().equals(current.get(h.documentId())) && eligible.contains(h.documentId()))
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
        var document = new DocumentId(id);
        if (!access.canRead(actor, document) || !documents.isCurrent(tenant, document, generation, search.identity())) {
            throw new SearchDocumentUnavailableException();
        }
        var chunks = documents.read(tenant, document, generation).orElseThrow(SearchDocumentUnavailableException::new);
        int start = Math.min(from, chunks.chunks().size());
        int end = Math.min(start + 20, chunks.chunks().size());
        return new SearchDocument(id, generation, chunks.title(), chunks.chunks().subList(start, end).stream()
                .map(c -> new SearchPage.Passage(c.ordinal(), c.content(), c.provenanceJson())).toList(),
                start, chunks.chunks().size(), end < chunks.chunks().size());
    }
}
