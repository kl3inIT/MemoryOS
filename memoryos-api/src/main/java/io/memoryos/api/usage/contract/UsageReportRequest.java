package io.memoryos.api.usage.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(name = "UsageReportRequest", description = "An inclusive UTC day range of at most 366 days")
public record UsageReportRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate from,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate to) {}
