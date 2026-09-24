package io.memoryos.audit;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the Tenant's audit stream for people IAM lets read it ({@link AuditReaders}). The stream only grows, so pages
 * are keyed by {@code (occurred_at, id)} rather than by offset: a page read while events arrive neither skips nor
 * repeats one. Exporting it is itself recorded.
 */
@Service
public class AuditLog {
    public static final int MAX_PAGE = 100;
    /** Bounds one export, so a request cannot hold a connection over the whole history. */
    public static final int MAX_EXPORT = 50_000;
    static final Duration MAX_PERIOD = Duration.ofDays(366);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final AuditReaders readers;
    private final AuditTrail trail;

    public AuditLog(JdbcClient jdbc, AuditReaders readers, AuditTrail trail) {
        this.jdbc = jdbc;
        this.readers = readers;
        this.trail = trail;
    }

    /** Every filter is optional; {@code text} matches the actor's name or e-mail and the resource's name. */
    public record Query(@Nullable Instant from, @Nullable Instant to, @Nullable String text, @Nullable AuditEventClass eventClass,
                        @Nullable String action, @Nullable AuditOutcome outcome, @Nullable UUID actor,
                        @Nullable String resourceType, @Nullable String resourceId) {
        public Query {
            if (from != null && to != null && !from.isBefore(to)) throw AuditException.invalid("The period must end after it starts.");
            if (from != null && to != null && Duration.between(from, to).compareTo(MAX_PERIOD) > 0)
                throw AuditException.invalid("Choose a period of at most 366 days.");
            text = text == null || text.isBlank() ? null : text.strip();
            if (text != null && text.length() > 200) throw AuditException.invalid("Search text is too long.");
            if (action != null && AuditAction.of(action).isEmpty()) throw AuditException.invalid("Unknown action.");
        }
    }

    public record Event(UUID id, Instant occurredAt, String action, AuditEventClass eventClass, AuditOutcome outcome,
                        @Nullable UUID actorId, @Nullable String actorLabel, @Nullable String actorEmail,
                        @Nullable String resourceType, @Nullable String resourceId, @Nullable String resourceLabel,
                        Map<String, Object> details, @Nullable String traceId, @Nullable String endpoint,
                        @Nullable String sourceIp) {}

    public record Page(List<Event> items, @Nullable String nextCursor) {}

    @Transactional(readOnly = true)
    public Page page(UUID reader, Query query, @Nullable String cursor, int size) {
        if (size < 1 || size > MAX_PAGE) throw AuditException.invalid("Page size must be between 1 and 100.");
        var tenant = reader(reader);
        var after = cursor == null ? null : Cursor.decode(cursor);
        var rows = select(tenant, query, after, size + 1);
        boolean more = rows.size() > size;
        var items = more ? rows.subList(0, size) : rows;
        return new Page(List.copyOf(items), more ? Cursor.encode(items.getLast()) : null);
    }

    @Transactional(readOnly = true)
    public Event get(UUID reader, UUID id) {
        var tenant = reader(reader);
        return jdbc.sql("SELECT * FROM audit_event WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant).param("id", id).query(AuditLog::event).optional()
                .orElseThrow(AuditException::notFound);
    }

    /**
     * Streams the events the filters select, newest first, and records that they left the system. The record is written
     * outside this read, so an export that breaks halfway still leaves evidence of what was read before it broke.
     */
    @Transactional(readOnly = true)
    public int export(UUID reader, Query query, Consumer<Event> sink) {
        var tenant = reader(reader);
        int rows = 0;
        Cursor after = null;
        try {
            while (rows < MAX_EXPORT) {
                var page = select(tenant, query, after, Math.min(1000, MAX_EXPORT - rows));
                if (page.isEmpty()) break;
                // Counted one by one, so a sink that breaks mid-page reports the rows it accepted.
                for (Event event : page) {
                    sink.accept(event);
                    rows++;
                }
                after = new Cursor(page.getLast().occurredAt(), page.getLast().id());
            }
        } catch (RuntimeException failure) {
            recordExport(tenant, reader, query, rows, AuditOutcome.FAILURE);
            throw failure;
        }
        recordExport(tenant, reader, query, rows, AuditOutcome.SUCCESS);
        return rows;
    }

    private void recordExport(UUID tenant, UUID reader, Query query, int rows, AuditOutcome outcome) {
        trail.recordSeparately(AuditRecord.of(AuditAction.AUDIT_EXPORT, tenant).actor(reader)
                .resource("AUDIT_LOG", null, null).outcome(outcome)
                .detail("from", query.from() == null ? null : query.from().toString())
                .detail("to", query.to() == null ? null : query.to().toString()).detail("rows", rows).build());
    }

    /** Refuses anyone who may not read the stream; for reads that need no row, such as the action catalog. */
    @Transactional(readOnly = true)
    public void requireReader(UUID reader) {
        reader(reader);
    }

    private UUID reader(UUID reader) {
        return readers.requireReader(reader);
    }

    private List<Event> select(UUID tenant, Query query, @Nullable Cursor after, int limit) {
        return jdbc.sql("""
                SELECT * FROM audit_event
                WHERE tenant_id = :tenant
                  AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
                  AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < :to)
                  AND (CAST(:class AS varchar) IS NULL OR event_class = :class)
                  AND (CAST(:action AS varchar) IS NULL OR action = :action)
                  AND (CAST(:outcome AS varchar) IS NULL OR outcome = :outcome)
                  AND (CAST(:actor AS uuid) IS NULL OR actor_id = :actor)
                  AND (CAST(:resourceType AS varchar) IS NULL OR resource_type = :resourceType)
                  AND (CAST(:resourceId AS varchar) IS NULL OR resource_id = :resourceId)
                  AND (CAST(:text AS varchar) IS NULL OR actor_label ILIKE :pattern OR actor_email ILIKE :pattern
                       OR resource_label ILIKE :pattern)
                  AND (CAST(:afterAt AS timestamptz) IS NULL OR (occurred_at, id) < (:afterAt, :afterId))
                ORDER BY occurred_at DESC, id DESC
                LIMIT :limit
                """)
                .param("tenant", tenant)
                .param("from", query.from() == null ? null : Timestamp.from(query.from()), java.sql.Types.TIMESTAMP)
                .param("to", query.to() == null ? null : Timestamp.from(query.to()), java.sql.Types.TIMESTAMP)
                .param("class", query.eventClass() == null ? null : query.eventClass().name(), java.sql.Types.VARCHAR)
                .param("action", query.action(), java.sql.Types.VARCHAR)
                .param("outcome", query.outcome() == null ? null : query.outcome().name(), java.sql.Types.VARCHAR)
                .param("actor", query.actor(), java.sql.Types.OTHER)
                .param("resourceType", query.resourceType(), java.sql.Types.VARCHAR)
                .param("resourceId", query.resourceId(), java.sql.Types.VARCHAR)
                .param("text", query.text(), java.sql.Types.VARCHAR)
                .param("pattern", query.text() == null ? null : "%" + escape(query.text()) + "%", java.sql.Types.VARCHAR)
                .param("afterAt", after == null ? null : Timestamp.from(after.at()), java.sql.Types.TIMESTAMP)
                .param("afterId", after == null ? null : after.id(), java.sql.Types.OTHER)
                .param("limit", limit)
                .query(AuditLog::event).list();
    }

    /** A search for a literal percent sign or underscore finds that character, not everything. */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @SuppressWarnings("unchecked")
    private static Event event(ResultSet r, int ignored) throws SQLException {
        Map<String, Object> details;
        try {
            details = JSON.readValue(r.getString("details"), Map.class);
        } catch (RuntimeException unreadable) {
            details = Map.of();
        }
        return new Event(r.getObject("id", UUID.class), r.getTimestamp("occurred_at").toInstant(), r.getString("action"),
                AuditEventClass.valueOf(r.getString("event_class")), AuditOutcome.valueOf(r.getString("outcome")),
                r.getObject("actor_id", UUID.class), r.getString("actor_label"), r.getString("actor_email"),
                r.getString("resource_type"), r.getString("resource_id"), r.getString("resource_label"), details,
                r.getString("trace_id"), r.getString("endpoint"), r.getString("source_ip"));
    }

    /** An opaque position in the stream: the last event a page returned. */
    record Cursor(Instant at, UUID id) {
        static String encode(Event last) {
            String raw = last.occurredAt().toString() + "|" + last.id();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int bar = raw.indexOf('|');
                return new Cursor(Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
            } catch (RuntimeException invalid) {
                throw AuditException.invalid("Invalid cursor.");
            }
        }
    }
}
