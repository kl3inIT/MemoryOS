package io.memoryos.api.audit.contract;

import io.memoryos.audit.AuditEventClass;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AuditCatalogAction")
public record AuditCatalogActionResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String action,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AuditEventClass eventClass) {}
