package io.memoryos.api.users.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Schema(name = "ReplaceUserGroupsRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReplaceUserGroupsRequest(
        @NotNull
        @Size(max = 100, message = "Select no more than 100 groups.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
        List<@NotNull UUID> groupIds
) {
    public ReplaceUserGroupsRequest {
        groupIds = groupIds == null ? null : List.copyOf(groupIds);
    }
}
