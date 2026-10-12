package io.memoryos.connector;

import org.springframework.context.annotation.ComponentScan;

/**
 * The Worker runs the whole connector module: source sync, selection validation, indexing and cleanup work. The Worker imports this class and scans nothing
 * of the module itself; it is deliberately not a component, so the API, which scans all of {@code io.memoryos},
 * registers these beans only once.
 */
@ComponentScan
public class ConnectorWorkerComponents {
}
