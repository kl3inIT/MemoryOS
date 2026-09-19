package io.memoryos.api.source.contract;

import io.memoryos.connector.SharePointCredentialService.CredentialView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Never carries the client secret or the private key; only what an administrator needs to recognise them. */
@Schema(name = "SharePointCredentialResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SharePointCredentialResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID directoryId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID clientId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String cloud,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String authMethod,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
        @Nullable String certificateThumbprint,
        @Nullable Instant certificateNotAfter,
        @Nullable String tenantHost,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long credentialRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sourceCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> actions) {

    public static SharePointCredentialResponse from(CredentialView credential) {
        return new SharePointCredentialResponse(credential.id().value(), credential.name(), credential.directoryId(),
                credential.clientId(), credential.cloud(), credential.authMethod(), credential.status(),
                credential.certificateThumbprint(), credential.certificateNotAfter(), credential.tenantHost(),
                credential.credentialRevision(), credential.createdAt(), credential.updatedAt(),
                credential.sourceCount(), credential.actions());
    }
}
