package io.memoryos.api.usage;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.usage.contract.UsageReportRequest;
import io.memoryos.api.usage.contract.UsageReportResponse;
import io.memoryos.iam.IdentityContext;
import io.memoryos.usage.report.UsageReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@ApiResponse(responseCode = "400", description = "Invalid period")
@ApiResponse(responseCode = "403", description = "Model management, Tenant membership or CSRF requirement not met")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class UsageReportController {
    private final UsageReportService reports;

    UsageReportController(UsageReportService reports) { this.reports = reports; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "requestUsageReport", summary = "Queue a usage report for a UTC day range; requires model management")
    @ApiResponse(responseCode = "202", description = "Queued; the Worker builds it in the background", useReturnTypeSchema = true)
    UsageReportResponse request(@CurrentActor IdentityContext identity, @RequestBody UsageReportRequest body) {
        return UsageReportResponse.from(reports.request(identity.actorId(), body.from(), body.to()));
    }

    @GetMapping
    @Operation(operationId = "listUsageReports", summary = "The Tenant's usage reports, newest first; requires model management")
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    List<UsageReportResponse> list(@CurrentActor IdentityContext identity) {
        return reports.list(identity.actorId()).stream().map(UsageReportResponse::from).toList();
    }

    @GetMapping(value = "/{reportId}/content", produces = "application/zip")
    @Operation(operationId = "downloadUsageReport", summary = "Download a ready usage report as a ZIP; requires model management")
    @ApiResponse(responseCode = "200", description = "ZIP bytes", content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "No ready report with this id in the Tenant")
    void download(@CurrentActor IdentityContext identity, @PathVariable UUID reportId,
                  HttpServletResponse response) throws IOException {
        var download = reports.open(identity.actorId(), reportId);
        try (var content = download.content()) {
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                    .filename(download.filename(), StandardCharsets.UTF_8).build().toString());
            response.setContentLengthLong(content.metadata().sizeBytes());
            content.inputStream().transferTo(response.getOutputStream());
        }
    }
}
