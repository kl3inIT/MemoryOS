package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "GoogleDriveAuthorizationResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GoogleDriveAuthorizationResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String authorizationUrl) {
    @Override public String toString() { return "GoogleDriveAuthorizationResponse[redacted]"; }
}
