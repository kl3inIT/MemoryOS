package io.memoryos.api.source.contract;

import io.memoryos.connector.LarkSourceService.Scope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** What to synchronize: the Wiki spaces and Drive folders shared with the credential's app, and how often. */
@Schema(name = "LarkScopeRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LarkScopeRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid LarkRootRequest> roots,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Min(1) @Max(525600) int syncIntervalMinutes) {

    public Scope toScope() {
        return new Scope(roots.stream().map(LarkRootRequest::toRoot).toList(), syncIntervalMinutes);
    }
}
