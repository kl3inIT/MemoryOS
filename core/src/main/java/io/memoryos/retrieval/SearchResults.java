package io.memoryos.retrieval;

import io.memoryos.iam.TenantId;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.Comparator;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** Only Retrieval creates this authorized candidate set. It is not an HTTP or model input. */
public final class SearchResults {
    private final TenantId tenant;
    private final List<SearchHit> hits;
    private final Set<SearchHit> authorized;
    SearchResults(TenantId tenant, List<SearchHit> hits) {
        this.tenant = tenant; this.hits = List.copyOf(hits); this.authorized = Set.copyOf(hits);
    }
    TenantId tenant() { return tenant; }
    public List<SearchHit> hits() { return hits; }
    boolean contains(SearchSection section) { return authorized.containsAll(section.chunks()); }

    public List<SearchSection> sections() {
        var ranks = new HashMap<SearchHit, Integer>();
        for (int i = 0; i < hits.size(); i++) ranks.putIfAbsent(hits.get(i), i);
        var byDocument = new LinkedHashMap<String, TreeMap<Integer, SearchHit>>();
        for (var hit : hits) byDocument.computeIfAbsent(hit.documentId() + ":" + hit.generation(), _ -> new TreeMap<>())
                .putIfAbsent(hit.ordinal(), hit);
        var sections = new ArrayList<SearchSection>();
        for (var ordered : byDocument.values()) {
            var adjacent = new ArrayList<SearchHit>();
            for (var hit : ordered.values()) {
                if (!adjacent.isEmpty() && hit.ordinal() != adjacent.getLast().ordinal() + 1) {
                    sections.add(section(adjacent, ranks));
                    adjacent.clear();
                }
                adjacent.add(hit);
            }
            if (!adjacent.isEmpty()) sections.add(section(adjacent, ranks));
        }
        return sections.stream().sorted(Comparator.comparingInt(s -> ranks.get(s.anchor()))).toList();
    }

    private static SearchSection section(List<SearchHit> chunks, Map<SearchHit, Integer> ranks) {
        var anchor = chunks.stream().min(Comparator.comparingInt(ranks::get)).orElseThrow();
        return new SearchSection(anchor, chunks);
    }
}
