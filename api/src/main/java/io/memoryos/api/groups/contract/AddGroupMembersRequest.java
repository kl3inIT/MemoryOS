package io.memoryos.api.groups.contract;

import io.memoryos.iam.ActorId;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Schema(name = "AddGroupMembersRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record AddGroupMembersRequest(
        @NotEmpty(message = "Select at least one user.")
        @Size(max = 100, message = "Select no more than 100 users.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        List<@NotNull UUID> actorIds
) {
    public AddGroupMembersRequest {
        actorIds = actorIds == null ? null : List.copyOf(actorIds);
    }

    public List<ActorId> toActorIds() {
        return actorIds.stream().map(ActorId::new).toList();
    }
}
