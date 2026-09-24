package io.memoryos.usage.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record UsageReport(UUID id, UUID requestedBy, String requester, LocalDate from, LocalDate to, UsageReportStatus status,
                          @Nullable Long sizeBytes, boolean hasPdf, @Nullable String failure, Instant createdAt,
                          @Nullable Instant finishedAt, @Nullable String objectKey) {}
