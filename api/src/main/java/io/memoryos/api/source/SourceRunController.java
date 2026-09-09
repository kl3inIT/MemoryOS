package io.memoryos.api.source;

import io.memoryos.api.source.contract.SourceRunErrorPageResponse;
import io.memoryos.api.source.contract.SourceRunPageResponse;
import io.memoryos.api.source.contract.SourceRunResponse;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceRunHistoryService;
import io.memoryos.connector.SourceRunStatus;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources/{sourceId}/runs")
@Tag(name = "Sources")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
final class SourceRunController {
    private final SourceRunHistoryService history;

    SourceRunController(SourceRunHistoryService history) { this.history = history; }

    @Operation(operationId = "listSourceRuns", summary = "List source synchronization runs and independent activity summaries")
    @GetMapping
    SourceRunPageResponse list(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sourceId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Nullable SourceRunStatus status,
            @RequestParam(required = false) @Nullable SourceRunTrigger trigger,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant to
    ) {
        return SourceRunPageResponse.from(history.list(identity.actorId(), new SourceId(sourceId),
                new SourceRunHistoryService.Query(cursor, size, status, trigger, from, to)));
    }

    @Operation(operationId = "getSourceRun", summary = "Get acquisition and owned indexing outcomes for one source run")
    @GetMapping("/{runId}")
    SourceRunResponse get(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sourceId, @PathVariable UUID runId
    ) {
        return SourceRunResponse.from(history.get(identity.actorId(), new SourceId(sourceId), runId));
    }

    @Operation(operationId = "listSourceRunErrors", summary = "List retained safe run and file errors")
    @GetMapping("/{runId}/errors")
    SourceRunErrorPageResponse errors(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sourceId, @PathVariable UUID runId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size
    ) {
        return SourceRunErrorPageResponse.from(history.errors(identity.actorId(), new SourceId(sourceId), runId, cursor, size));
    }
}
