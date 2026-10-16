package io.memoryos.api.source.contract;

import io.memoryos.connector.SharePointSourceService.Scope;
import io.memoryos.connector.SharePointSourceService.ScopeMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What to synchronize. {@code siteUrls} is empty for {@link ScopeMode#ALL_SITES}, where every site the
 * application can read is in scope except the excluded ones.
 */
@Schema(name = "SharePointScopeRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SharePointScopeRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ScopeMode scopeMode,
        @Schema(description = "Site, library or folder addresses; sharing links are accepted")
        @Size(max = 1000) @Nullable List<@Size(max = 2048) String> siteUrls,
        @Size(max = 100) @Nullable List<@Size(max = 512) String> excludedSites,
        @Size(max = 100) @Nullable List<@Size(max = 512) String> excludedPaths,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean includeDocuments,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean includePages,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Min(1) int syncIntervalMinutes,
        @Schema(description = "Hours between prune runs; 0 disables pruning") @Min(0) @Max(8760) int pruneIntervalHours) {

    public Scope toScope() {
        return new Scope(scopeMode, siteUrls == null ? List.of() : siteUrls,
                excludedSites == null ? List.of() : excludedSites, excludedPaths == null ? List.of() : excludedPaths,
                includeDocuments, includePages, syncIntervalMinutes, pruneIntervalHours);
    }
}
