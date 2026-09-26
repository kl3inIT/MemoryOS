package io.memoryos.audit;

import io.memoryos.audit.persistence.JdbcAuditEventRepository;
import io.memoryos.shared.ActorId;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
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

    private final JdbcAuditEventRepository events;
    private final AuditRequestContext requestContext;
    private final MeterRegistry meters;
    private final TransactionTemplate separate;

    /** The API supplies the request it is serving; the Worker has none, and records the trace alone. */
    @Autowired
    public AuditTrail(JdbcAuditEventRepository events, ObjectProvider<AuditRequestContext> requestContext,
                      MeterRegistry meters, PlatformTransactionManager transactions) {
        this(events, requestContext.getIfAvailable(() -> AuditRequestContext.TRACE_ONLY), meters, transactions);
    }

    public AuditTrail(JdbcAuditEventRepository events, AuditRequestContext requestContext, MeterRegistry meters,
                      PlatformTransactionManager transactions) {
        this.events = events;
        this.requestContext = requestContext;
        this.meters = meters;
        this.separate = new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records an event outside the caller's transaction: a refused attempt, whose own transaction ends in the exception
     * that refuses it; a failed sign-in, which has no transaction of its own; or a change settled in Keycloak, which
     * no database transaction covers.
     */
    public void recordSeparately(AuditRecord event) {
        // A refusal is usually raised while its transaction holds the Tenant lock, which the event's own insert
        // would wait on for its foreign key: write it once that transaction has ended, whichever way it ended.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) { writeSeparately(event); }
                    });
            return;
        }
        writeSeparately(event);
    }

    private void writeSeparately(AuditRecord event) {
        try {
            separate.executeWithoutResult(ignored -> record(event));
        } catch (RuntimeException failure) {
            meters.counter("memoryos.audit.write.failures", "action", event.action().value()).increment();
            logFailure("audit.event.store_failed", event.action().value(), failure, "Audit event could not be stored");
        }
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
        String actorLabel = event.actorLabel();
        String actorEmail = null;
        try {
            events.savepoint();
            // Inside the savepoint: in PostgreSQL any failed statement would otherwise abort the caller's transaction.
            Person actor = event.actor() == null ? null : person(event.actor());
            if (actorLabel == null && actor != null) actorLabel = actor.label();
            if (actor != null) actorEmail = actor.email();
            events.insert(new JdbcAuditEventRepository.NewEvent(id, event.tenant().value(), at, event.action().value(),
                    event.action().eventClass().name(), event.outcome().name(), event.actor() == null ? null : event.actor().value(), actorLabel, actorEmail,
                    event.resourceType(), event.resourceId(), event.resourceLabel(), details, requestContext.traceId(),
                    requestContext.endpoint(), requestContext.sourceIp(), SCHEMA_VERSION));
            events.releaseSavepoint();
        } catch (RuntimeException failure) {
            // The stream is evidence of what was recorded, not proof that nothing else happened: a gap shows up here.
            meters.counter("memoryos.audit.write.failures", "action", event.action().value()).increment();
            logFailure("audit.event.store_failed", event.action().value(), failure,
                    "Audit event could not be stored; the change it records still committed");
            try {
                events.rollbackToSavepoint();
            } catch (RuntimeException lost) {
                logFailure("audit.savepoint.rollback_failed", null, lost,
                        "Audit savepoint could not be released; the caller's transaction may fail");
            }
        }
        emit(id, at, event, actorLabel, details);
    }

    /** A person as the record names them: display name and e-mail as they are now, so the row keeps them later. */
    public record Person(String label, @Nullable String email) {}

    /**
     * Who {@code actor} is, for a record that names them. Read in the caller's transaction, so it sees a person the
     * same change has just admitted.
     */
    public Person person(ActorId actor) {
        return events.person(actor.value())
                .orElse(new Person(actor.value().toString(), null));
    }

    /** One JSON line per event, on a logger named for its class, as Onyx emits for a SIEM. */
    private void emit(UUID id, Instant at, AuditRecord event, @Nullable String actorLabel,
                      String details) {
        try {
            var line = new LinkedHashMap<String, @Nullable Object>();
            line.put("audit_schema_version", SCHEMA_VERSION);
            line.put("id", id.toString());
            line.put("ts", at.toString());
            line.put("action", event.action().value());
            line.put("ocsf_class", event.action().eventClass().ocsfClassId());
            line.put("outcome", event.outcome().name().toLowerCase(Locale.ROOT));
            line.put("tenant_id", event.tenant().value().toString());
            line.put("actor_id", event.actor() == null ? null : event.actor().value().toString());
            line.put("actor", actorLabel);
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

    /** Logs a failed audit write by type and, when the database gave one, its SQLState; never the driver's message. */
    private static void logFailure(String name, @Nullable String action, Throwable failure, String message) {
        LoggingEventBuilder log = LOG.atError().addKeyValue("event", name)
                .addKeyValue("error_type", failure.getClass().getName());
        if (action != null) log = log.addKeyValue("action", action);
        String state = sqlState(failure);
        if (state != null) log = log.addKeyValue("error_code", state);
        log.log(message);
    }

    /** The SQLState of the failure, a bounded code; the driver's message may quote the values it refused. */
    private static @Nullable String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql) return sql.getSQLState();
        return null;
    }
}
