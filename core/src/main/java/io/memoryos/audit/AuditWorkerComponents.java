package io.memoryos.audit;

import org.springframework.context.annotation.ComponentScan;

/**
 * The Worker runs the whole audit module: recording audit events and the stream's retention. The Worker imports this class and scans nothing
 * of the module itself; it is deliberately not a component, so the API, which scans all of {@code io.memoryos},
 * registers these beans only once.
 */
@ComponentScan
public class AuditWorkerComponents {
}
