package io.memoryos.api.usage;

import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.usage.persistence.UsageReportRepository;
import io.memoryos.usage.report.UsageReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/ai-costs/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI costs")
@ApiResponse(responseCode = "400", description = "Invalid period", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Model management, Tenant membership or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class UsageReportController {
    private final UsageReportService reports;

    UsageReportController(UsageReportService reports) { this.reports = reports; }

    @Schema(name = "UsageReportRequest", description = "An inclusive UTC day range of at most 366 days")
    record Request(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate from,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate to) {}

    @Schema(name = "UsageReport", description = "A generated usage report: a ZIP of usage_by_user.csv, users.csv and, unless it "
            + "could not be rendered, usage_report.pdf")
    record Response(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate from,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate to,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PENDING", "RUNNING", "READY", "FAILED"})
                    String status,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Display name or e-mail of the manager who asked for it")
                    String requester,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Long sizeBytes,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasPdf,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String failure,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant finishedAt) {
        static Response from(UsageReportRepository.Report report) {
            return new Response(report.id(), report.from(), report.to(), report.status().name(), report.requester(),
                    report.sizeBytes(), report.hasPdf(), report.failure(), report.createdAt(), report.finishedAt());
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "requestUsageReport", summary = "Queue a usage report for a UTC day range; requires model management")
    @ApiResponse(responseCode = "202", description = "Queued; the Worker builds it in the background", useReturnTypeSchema = true)
    Response request(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody Request body) {
        return Response.from(reports.request(identity.actorId(), body.from(), body.to()));
    }

    @GetMapping
    @Operation(operationId = "listUsageReports", summary = "The Tenant's usage reports, newest first; requires model management")
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    List<Response> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return reports.list(identity.actorId()).stream().map(Response::from).toList();
    }

    @GetMapping(value = "/{reportId}/content", produces = "application/zip")
    @Operation(operationId = "downloadUsageReport", summary = "Download a ready usage report as a ZIP; requires model management")
    @ApiResponse(responseCode = "200", description = "ZIP bytes", content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "No ready report with this id in the Tenant", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    void download(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID reportId,
                  HttpServletResponse response) throws IOException {
        var download = reports.open(identity.actorId(), reportId);
        try (var content = download.content()) {
            response.setContentType("application/zip");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                    .filename(download.filename(), StandardCharsets.UTF_8).build().toString());
            response.setContentLengthLong(content.metadata().sizeBytes());
            content.inputStream().transferTo(response.getOutputStream());
        }
    }
}
