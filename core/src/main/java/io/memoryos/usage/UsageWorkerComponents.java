package io.memoryos.usage;

import org.springframework.context.annotation.ComponentScan;

/**
 * The Worker runs the whole AI usage module: recording what a read spent, limits and usage reports. The Worker imports this class and scans nothing
 * of the module itself; it is deliberately not a component, so the API, which scans all of {@code io.memoryos},
 * registers these beans only once.
 */
@ComponentScan
public class UsageWorkerComponents {
}
