package io.memoryos.api.audit.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "AuditEventPage")
public record AuditEventPageResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AuditEventResponse> items,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                                             description = "Pass back to read the next, older page; null on the last page")
                                     @Nullable String nextCursor) {}
