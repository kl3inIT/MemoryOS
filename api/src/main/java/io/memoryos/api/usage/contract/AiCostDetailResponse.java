package io.memoryos.api.usage.contract;

import io.memoryos.usage.AiCostService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "AiCostDetail")
public record AiCostDetailResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AiCostSummaryResponse summary,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AiCostDayResponse> daily,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AiCostRowResponse> models,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AiCostRowResponse> flows,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AiCostRowResponse> providers
) {
    public static AiCostDetailResponse from(AiCostService.Detail value) {
        return new AiCostDetailResponse(AiCostSummaryResponse.from(value.totals()),
                value.daily().stream().map(AiCostDayResponse::from).toList(),
                value.models().stream().map(AiCostRowResponse::from).toList(),
                value.flows().stream().map(AiCostRowResponse::from).toList(),
                value.providers().stream().map(AiCostRowResponse::from).toList());
    }
}
