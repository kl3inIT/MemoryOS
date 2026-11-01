package io.memoryos.api.source.contract;

import io.memoryos.connector.LarkCredentialService.TestResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A working credential: the bot it is, and the read scopes still to grant to the tenant token. */
@Schema(name = "LarkCredentialTestResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LarkCredentialTestResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String botName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> missingScopes) {

    public static LarkCredentialTestResponse from(TestResult result) {
        return new LarkCredentialTestResponse(result.botName(), result.missingScopes().stream().sorted().toList());
    }
}
