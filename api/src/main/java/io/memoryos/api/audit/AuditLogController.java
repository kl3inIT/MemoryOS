package io.memoryos.api.audit;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditEventClass;
import io.memoryos.audit.AuditLog;
import io.memoryos.audit.AuditOutcome;
import io.memoryos.iam.IdentityContext;
import io.memoryos.shared.ActorId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping(value = "/api/audit", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Audit")
@ApiResponse(responseCode = "400", description = "Invalid filter or cursor", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Audit reading or Tenant membership requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class AuditLogController {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Characters spreadsheets read as the start of a formula, as the usage report guards them. */
    private static final String FORMULA_PREFIXES = "=+-@\t\r";

    private final AuditLog log;

    AuditLogController(AuditLog log) { this.log = log; }

    @Schema(name = "AuditEvent", description = "One recorded change: who, what and to which resource, with the declared details of its action")
    record EventResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant occurredAt,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "An append-only action value such as user.deactivate")
                         String action,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AuditEventClass eventClass,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AuditOutcome outcome,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable UUID actorId,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String actorLabel,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String actorEmail,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String resourceType,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String resourceId,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String resourceLabel,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                         Map<String, Object> details,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String traceId,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String endpoint,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String sourceIp) {
        static EventResponse from(AuditLog.Event event) {
            return new EventResponse(event.id(), event.occurredAt(), event.action(), event.eventClass(), event.outcome(),
                    actorUuid(event), event.actorLabel(), event.actorEmail(), event.resourceType(), event.resourceId(),
                    event.resourceLabel(), event.details(), event.traceId(), event.endpoint(), event.sourceIp());
        }
    }

    @Schema(name = "AuditEventPage")
    record PageResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<EventResponse> items,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                                description = "Pass back to read the next, older page; null on the last page")
                        @Nullable String nextCursor) {}

    @Schema(name = "AuditCatalog", description = "Every action the stream can hold, newest catalog first; values never change meaning")
    record CatalogResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CatalogAction> actions) {}

    @Schema(name = "AuditCatalogAction")
    record CatalogAction(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String action,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AuditEventClass eventClass) {}

    @GetMapping("/events")
    @Operation(operationId = "listAuditEvents", summary = "The Tenant's audit events, newest first, one page at a time; requires AUDIT_READ")
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    PageResponse events(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant to,
                        @RequestParam(required = false) @Nullable String q,
                        @RequestParam(required = false) @Nullable AuditEventClass eventClass,
                        @RequestParam(required = false) @Nullable String action,
                        @RequestParam(required = false) @Nullable AuditOutcome outcome,
                        @RequestParam(required = false) @Nullable UUID actorId,
                        @RequestParam(required = false) @Nullable String resourceType,
                        @RequestParam(required = false) @Nullable String resourceId,
                        @RequestParam(required = false) @Nullable String cursor,
                        @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100", defaultValue = "50"))
                        @RequestParam(defaultValue = "50") int size) {
        var page = log.page(identity.actorId(), new AuditLog.Query(from, to, q, eventClass, action, outcome, actor(actorId),
                resourceType, resourceId), cursor, size);
        return new PageResponse(page.items().stream().map(EventResponse::from).toList(), page.nextCursor());
    }

    @GetMapping("/events/{eventId}")
    @Operation(operationId = "getAuditEvent", summary = "One audit event; requires AUDIT_READ")
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "No event with this id in the Tenant", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    EventResponse event(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID eventId) {
        return EventResponse.from(log.get(identity.actorId(), eventId));
    }

    @GetMapping("/catalog")
    @Operation(operationId = "getAuditCatalog", summary = "Every action the audit stream can hold, for the viewer's action filter; requires AUDIT_READ")
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    CatalogResponse catalog(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        log.requireReader(identity.actorId());
        return new CatalogResponse(Arrays.stream(AuditAction.values())
                .map(action -> new CatalogAction(action.value(), action.eventClass())).toList());
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(operationId = "exportAuditEvents", summary = "The events the filters select as CSV, at most 50,000 rows; the export is itself recorded; requires AUDIT_READ")
    @ApiResponse(responseCode = "200", description = "CSV", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    void export(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant from,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant to,
                @RequestParam(required = false) @Nullable String q,
                @RequestParam(required = false) @Nullable AuditEventClass eventClass,
                @RequestParam(required = false) @Nullable String action,
                @RequestParam(required = false) @Nullable AuditOutcome outcome,
                @RequestParam(required = false) @Nullable UUID actorId,
                HttpServletResponse response) throws IOException {
        var query = new AuditLog.Query(from, to, q, eventClass, action, outcome, actor(actorId), null, null);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", ContentDisposition.attachment()
                .filename("audit-log_" + java.time.LocalDate.now(java.time.ZoneOffset.UTC) + ".csv", StandardCharsets.UTF_8)
                .build().toString());
        Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        // A byte-order mark, so a spreadsheet opens Vietnamese names as UTF-8.
        writer.write('﻿');
        var csv = new CSVPrinter(writer, CSVFormat.DEFAULT);
        csv.printRecord("occurred_at", "action", "event_class", "outcome", "actor_id", "actor", "actor_email", "resource_type",
                "resource_id", "resource", "details", "source_ip", "endpoint", "trace_id");
        log.export(identity.actorId(), query, event -> {
            try {
                csv.printRecord(event.occurredAt(), event.action(), event.eventClass(), event.outcome(), actorUuid(event),
                        guard(event.actorLabel()), guard(event.actorEmail()), event.resourceType(), guard(event.resourceId()),
                        guard(event.resourceLabel()), guard(JSON.writeValueAsString(event.details())), event.sourceIp(),
                        event.endpoint(), event.traceId());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        csv.flush();
    }

    /** User-controlled text only; the usage report guards the same prefixes. */
    private static @Nullable UUID actorUuid(AuditLog.Event event) {
        return event.actorId() == null ? null : event.actorId().value();
    }

    private static @Nullable ActorId actor(@Nullable UUID actorId) {
        return actorId == null ? null : new ActorId(actorId);
    }

    private static @Nullable String guard(@Nullable String value) {
        if (value == null || value.isEmpty()) return value;
        return FORMULA_PREFIXES.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }
}
