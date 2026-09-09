package io.memoryos.api.source.contract;

import io.memoryos.connector.GoogleDriveAuthorizationService.CredentialView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "GoogleDriveCredentialResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GoogleDriveCredentialResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accountEmail,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long credentialRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean oauthClientConfigured,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sourceCount) {
    public static GoogleDriveCredentialResponse from(CredentialView credential) {
        return new GoogleDriveCredentialResponse(credential.id().value(), credential.name(), credential.accountEmail(),
                credential.status(), credential.credentialRevision(), credential.oauthClientConfigured(),
                credential.createdAt(), credential.updatedAt(), credential.sourceCount());
    }
}
