package io.memoryos.api.identity.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LanguagePreference(
        @NotNull @Pattern(regexp = "vi|en")
        @Schema(allowableValues = {"vi", "en"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String uiLanguage
) {}
