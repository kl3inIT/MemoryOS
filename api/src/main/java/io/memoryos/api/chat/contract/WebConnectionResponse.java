package io.memoryos.api.chat.contract;

import io.memoryos.chat.web.WebProvider;
import io.memoryos.chat.web.WebConnectionService;
import io.swagger.v3.oas.annotations.media.Schema;

public record WebConnectionResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) WebProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String endpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String engineId,
        boolean credentialConfigured, boolean searchActive, boolean contentActive, long revision) {
    public static WebConnectionResponse from(WebConnectionService.View view) {
        return new WebConnectionResponse(view.provider(), view.endpoint(), view.engineId(), view.credentialConfigured(), view.searchActive(), view.contentActive(), view.revision());
    }
}
