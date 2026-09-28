package io.memoryos.retrieval.opensearch;

import org.opensearch.client.transport.OpenSearchTransport;
import tools.jackson.databind.ObjectMapper;

/** Builds the production OpenSearch gateway for tests outside this package. */
public final class TestSearchGateways {
    private TestSearchGateways() { }

    /**
     * A gateway over its own transport. The transport is an asynchronous HTTP client with its own I/O threads and
     * buffers, so the caller closes it when the test is done; left open, every test leaves a client behind in the JVM.
     */
    public record Opened(OpenSearchGateway gateway, OpenSearchTransport transport) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            transport.close();
        }
    }

    public static Opened open(SearchProperties properties, ObjectMapper mapper) throws Exception {
        var config = new SearchInfrastructureConfiguration();
        var transport = config.searchTransport(properties);
        return new Opened(new OpenSearchGateway(config.searchClient(transport), mapper), transport);
    }
}
