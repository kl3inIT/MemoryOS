package io.memoryos.retrieval;

import io.memoryos.iam.TenantId;
import java.util.List;

/** Only Retrieval creates this authorized candidate set. It is not an HTTP or model input. */
public final class SearchResults {
    private final TenantId tenant;
    private final List<SearchHit> hits;
    SearchResults(TenantId tenant, List<SearchHit> hits) { this.tenant = tenant; this.hits = List.copyOf(hits); }
    TenantId tenant() { return tenant; }
    public List<SearchHit> hits() { return hits; }
}
