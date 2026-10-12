package io.memoryos.retrieval;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * The retrieval beans the Worker runs: the embedding client, the OpenSearch index and the search generations with
 * their embedding provider (MEM-135), plus the timings they report. Search itself, which answers a person and needs
 * their access, stays with the API. The Worker imports this class and scans nothing of the module itself; it is
 * deliberately not a component, so the API, which scans all of {@code io.memoryos}, registers these beans only once.
 */
@ComponentScan({"io.memoryos.retrieval.embedding", "io.memoryos.retrieval.opensearch", "io.memoryos.retrieval.settings"})
@Import(SearchTimings.class)
public class RetrievalWorkerComponents {
}
