package io.memoryos.api.identity.contract;

import io.memoryos.iam.PrincipalPerson;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "PrincipalPerson", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PrincipalPersonResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID actorId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String email
) {
    public static PrincipalPersonResponse from(PrincipalPerson person) {
        return new PrincipalPersonResponse(person.actorId().value(), person.name(), person.email());
    }
}
