package io.memoryos.api.chat.contract;

import io.memoryos.ai.ChatModelResolver;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatReportedModels", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChatReportedModelsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ReportedModel> models
) {
    public ChatReportedModelsResponse { models = List.copyOf(models); }

    public static ChatReportedModelsResponse from(List<ChatModelResolver.ReportedModelSpec> specs) {
        return new ChatReportedModelsResponse(specs.stream().map(spec -> new ReportedModel(spec.modelName(),
                spec.contextWindow(), spec.maxOutputTokens(),
                new Capabilities(spec.capabilities().toolCalling(), spec.capabilities().vision(), spec.capabilities().reasoning()),
                spec.pricing() == null ? null : new Pricing(spec.pricing().inputPerMillion(), spec.pricing().outputPerMillion()),
                spec.source().name().toLowerCase(java.util.Locale.ROOT))).toList());
    }

    /**
     * A reported model with the specs to add it without typing. {@code none} means nobody publishes them: the window
     * is Onyx's 32,000-token fallback and the administrator should review it. A null output limit sends no cap.
     */
    @Schema(name = "ChatReportedModel", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ReportedModel(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String modelName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int contextWindow,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}) @Nullable Integer maxOutputTokens,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Capabilities capabilities,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Pricing pricing,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"provider", "catalog", "none"},
                    description = "Where the specs come from: the endpoint itself, the installed catalog, or Onyx's defaults") String source) {}

    @Schema(name = "ChatReportedModelCapabilities", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Capabilities(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean toolCalling,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean vision,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reasoning) {}

    @Schema(name = "ChatReportedModelPricing", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Pricing(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) double inputPerMillion,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double outputPerMillion) {}
}
