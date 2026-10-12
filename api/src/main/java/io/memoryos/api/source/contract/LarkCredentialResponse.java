package io.memoryos.api.source.contract;

import io.memoryos.connector.LarkCredentialService.CredentialView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Never carries the App Secret; only what an administrator needs to recognise the app. */
@Schema(name = "LarkCredentialResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LarkCredentialResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String region,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String appId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
        @Schema(description = "The bot Lark named at the last verification: what is added to a space or a folder")
        @Nullable String botName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Read scopes Lark refused to the app's tenant token at the last verification")
        List<String> missingScopes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long credentialRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sourceCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> actions) {

    public static LarkCredentialResponse from(CredentialView credential) {
        return new LarkCredentialResponse(credential.id().value(), credential.name(), credential.region(),
                credential.appId(), credential.status(), credential.botName(), credential.missingScopes(),
                credential.credentialRevision(), credential.createdAt(), credential.updatedAt(),
                credential.sourceCount(), credential.actions());
    }
}
