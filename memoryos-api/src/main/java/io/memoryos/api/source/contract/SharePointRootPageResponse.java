package io.memoryos.api.source.contract;

import io.memoryos.connector.SharePointSourceService.RootKind;
import io.memoryos.connector.SharePointSourceService.RootPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "SharePointRootPageResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SharePointRootPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long scopeRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Root> roots,
        @Nullable String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long total) {

    public static SharePointRootPageResponse from(RootPage page) {
        return new SharePointRootPageResponse(page.scopeRevision(), page.roots().stream()
                .map(root -> new Root(root.url(), root.kind(), root.displayName(), root.verified())).toList(),
                page.nextCursor(), page.total());
    }

    @Schema(name = "SharePointRoot", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Root(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String url,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RootKind kind,
            @Nullable String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean verified) {
    }
}
