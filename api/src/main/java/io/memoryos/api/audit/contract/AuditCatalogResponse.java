package io.memoryos.api.audit.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "AuditCatalog", description = "Every action the stream can hold, newest catalog first; values never change meaning")
public record AuditCatalogResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AuditCatalogActionResponse> actions) {}
