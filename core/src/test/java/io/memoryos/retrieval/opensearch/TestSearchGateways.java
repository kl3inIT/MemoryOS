package io.memoryos.retrieval.opensearch;

import tools.jackson.databind.ObjectMapper;

/** Builds the production OpenSearch gateway for tests outside this package; the transport lives for the test JVM. */
public final class TestSearchGateways {
    private TestSearchGateways() { }

    public static OpenSearchGateway gateway(SearchProperties properties, ObjectMapper mapper) throws Exception {
        var config = new SearchInfrastructureConfiguration();
        return new OpenSearchGateway(config.searchClient(config.searchTransport(properties)), mapper);
    }
}
