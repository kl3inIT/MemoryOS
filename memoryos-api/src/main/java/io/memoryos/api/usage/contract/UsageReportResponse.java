package io.memoryos.api.usage.contract;

import io.memoryos.usage.report.UsageReport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "UsageReport", description = "A generated usage report: a ZIP of usage_by_user.csv, users.csv and, unless it "
        + "could not be rendered, usage_report.pdf")
public record UsageReportResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant finishedAt
) {
    public static UsageReportResponse from(UsageReport report) {
        return new UsageReportResponse(report.id(), report.from(), report.to(), report.status().name(), report.requester(),
                report.sizeBytes(), report.hasPdf(), report.failure(), report.createdAt(), report.finishedAt());
    }
}
