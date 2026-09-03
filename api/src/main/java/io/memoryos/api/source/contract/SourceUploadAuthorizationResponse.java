package io.memoryos.api.source.contract;

import io.memoryos.objectstorage.ObjectUploadAuthorization;
import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Schema(name = "SourceUploadAuthorization", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceUploadAuthorizationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID uploadId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String method,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) URI uploadUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> requiredHeaders,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt
) {
    public static SourceUploadAuthorizationResponse from(ObjectUploadAuthorization result) {
        return new SourceUploadAuthorizationResponse(
                result.uploadId().value(),
                result.authorization().method(),
                result.authorization().uri(),
                result.authorization().requiredHeaders(),
                result.authorization().expiresAt()
        );
    }
}
