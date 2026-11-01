package io.memoryos.retrieval.opensearch;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.io.IOException;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** The OpenSearch node of the search integration tests. */
public final class TestOpenSearch {
    private static final String IMAGE =
            "opensearchproject/opensearch:3.8.0@sha256:bcc1797519726ceb6d651d4a3e60b7c30da91793914a8dfe75fd441d4f641509";

    // Index settings for every index on the test node, through a template rather than the application's index
    // settings, which stay what production creates. Writes that wait for a refresh (refresh=wait_for) wait for the
    // index's next one, a second by default, so the node refreshes every 50 ms. The k-NN plugin builds a Faiss HNSW
    // graph for every flushed and merged segment by default, which took most of the node's CPU at 3072 dimensions;
    // the tests' few hundred chunks are searched exactly instead, with the same hits.
    private static final String TEST_INDEX_SETTINGS = """
            {"index_patterns":["*"],"priority":0,"template":{"settings":{
            "index.refresh_interval":"50ms","index.knn.advanced.approximate_threshold":-1}}}""";

    private TestOpenSearch() { }

    @SuppressWarnings("resource") // The caller's Testcontainers extension owns start and stop.
    public static GenericContainer<?> container() {
        return new Node()
                .withEnv("discovery.type", "single-node").withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true").withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200).waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(3)));
    }

    private static final class Node extends GenericContainer<Node> {
        Node() {
            super(IMAGE);
        }

        @Override
        protected void containerIsStarted(InspectContainerResponse containerInfo) {
            try {
                var result = execInContainer("curl", "-sf", "-XPUT", "http://127.0.0.1:9200/_index_template/test-index-settings",
                        "-H", "Content-Type: application/json", "-d", TEST_INDEX_SETTINGS);
                if (result.getExitCode() != 0) {
                    throw new IllegalStateException("The test index template was refused: " + result.getStdout());
                }
            } catch (IOException failure) {
                throw new IllegalStateException("The test index template could not be installed", failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted installing the test index template", interrupted);
            }
        }
    }
}
