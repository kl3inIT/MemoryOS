package io.memoryos.audit;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Where the recorded change was asked for. An event is evidence, so it carries what an investigation starts from: the
 * trace that reaches the rest of the logs, the endpoint, and the address the request came from.
 */
public interface AuditRequestContext {

    /** Nothing but the trace: what a Worker task or a test has. */
    AuditRequestContext TRACE_ONLY = new AuditRequestContext() {};

    default @Nullable String traceId() { return MDC.get("traceId"); }

    default @Nullable String endpoint() { return null; }

    /** The client address as the deployment resolves it, behind its own proxies. */
    default @Nullable String sourceIp() { return null; }
}
