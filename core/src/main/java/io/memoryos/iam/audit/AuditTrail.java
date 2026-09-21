package io.memoryos.iam.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the Tenant's audit stream (ADR 0013).
 *
 * <p>The event is written in the caller's transaction, so a change that rolls back leaves no record of having
 * happened. A failure to write it never fails the caller, as in Onyx: the insert runs inside a savepoint, and a
 * failure is rolled back to that savepoint, logged and counted, leaving the administrative change to commit. The
 * event is then re-emitted as one JSON line, so the evidence survives outside the database and a log shipper can
 * carry the stream to a SIEM without any further instrumentation.
 */
@Service
public class AuditTrail {
    private static final Logger LOG = LoggerFactory.getLogger(AuditTrail.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int SCHEMA_VERSION = 1;

    private final JdbcClient jdbc;
    private final AuditRequestContext requestContext;
    private final MeterRegistry meters;

    public AuditTrail(JdbcClient jdbc, AuditRequestContext requestContext, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.requestContext = requestContext;
        this.meters = meters;
    }

    /**
     * Records one event. Call this from the service that performs the change, inside its transaction, after the
     * change has been made and while the values it changed are still known.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditRecord event) {
        var at = Instant.now();
        UUID id = UUID.randomUUID();
        String details = details(event);
        try {
            jdbc.sql("SAVEPOINT memoryos_audit").update();
            jdbc.sql("""
                    INSERT INTO audit_event(id, tenant_id, occurred_at, action, event_class, outcome, actor_id, actor_label,
                        resource_type, resource_id, resource_label, details, trace_id, endpoint, source_ip, schema_version)
                    VALUES (:id, :tenant, :at, :action, :class, :outcome, :actor, :actorLabel, :resourceType, :resourceId,
                        :resourceLabel, CAST(:details AS jsonb), :trace, :endpoint, :ip, :version)
                    """)
                    .param("id", id).param("tenant", event.tenant().value()).param("at", java.sql.Timestamp.from(at))
                    .param("action", event.action().value()).param("class", event.action().eventClass().name())
                    .param("outcome", event.outcome().name())
                    .param("actor", event.actor() == null ? null : event.actor().value(), java.sql.Types.OTHER)
                    .param("actorLabel", event.actorLabel(), java.sql.Types.VARCHAR)
                    .param("resourceType", event.resourceType(), java.sql.Types.VARCHAR)
                    .param("resourceId", event.resourceId(), java.sql.Types.VARCHAR)
                    .param("resourceLabel", event.resourceLabel(), java.sql.Types.VARCHAR)
                    .param("details", details)
                    .param("trace", requestContext.traceId(), java.sql.Types.VARCHAR)
                    .param("endpoint", requestContext.endpoint(), java.sql.Types.VARCHAR)
                    .param("ip", requestContext.sourceIp(), java.sql.Types.VARCHAR)
                    .param("version", SCHEMA_VERSION)
                    .update();
            jdbc.sql("RELEASE SAVEPOINT memoryos_audit").update();
        } catch (RuntimeException failure) {
            // The stream is evidence of what was recorded, not proof that nothing else happened: a gap shows up here.
            meters.counter("memoryos.audit.write.failures", "action", event.action().value()).increment();
            LOG.error("Audit event {} could not be stored; the change it records still committed", event.action().value(),
                    failure);
            try {
                jdbc.sql("ROLLBACK TO SAVEPOINT memoryos_audit").update();
            } catch (RuntimeException lost) {
                LOG.error("Audit savepoint could not be released; the caller's transaction may fail", lost);
            }
        }
        emit(id, at, event, details);
    }

    /** One JSON line per event, on a logger named for its class, as Onyx emits for a SIEM. */
    private void emit(UUID id, Instant at, AuditRecord event, String details) {
        try {
            var line = new LinkedHashMap<String, Object>();
            line.put("audit_schema_version", SCHEMA_VERSION);
            line.put("id", id.toString());
            line.put("ts", at.toString());
            line.put("action", event.action().value());
            line.put("ocsf_class", event.action().eventClass().ocsfClassId());
            line.put("outcome", event.outcome().name().toLowerCase(java.util.Locale.ROOT));
            line.put("tenant_id", event.tenant().value().toString());
            line.put("actor_id", event.actor() == null ? null : event.actor().value().toString());
            line.put("actor", event.actorLabel());
            line.put("resource_type", event.resourceType());
            line.put("resource_id", event.resourceId());
            line.put("trace_id", requestContext.traceId());
            line.put("endpoint", requestContext.endpoint());
            line.put("source_ip", requestContext.sourceIp());
            line.put("details", JSON.readTree(details));
            LoggerFactory.getLogger(event.action().eventClass().loggerName()).info(JSON.writeValueAsString(line));
        } catch (RuntimeException ignored) {
            // The stored row is the record; the log line is a convenience for shipping it onward.
        }
    }

    private static String details(AuditRecord event) {
        Map<String, Object> declared = new LinkedHashMap<>();
        event.details().forEach((field, value) -> {
            if (value != null) declared.put(field, value);
        });
        return JSON.writeValueAsString(declared);
    }
}
