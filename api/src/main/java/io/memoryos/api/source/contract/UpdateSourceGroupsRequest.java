package io.memoryos.api.source.contract;

import io.memoryos.iam.GroupId;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Schema(name = "UpdateSourceGroupsRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateSourceGroupsRequest(
        @NotEmpty(message = "Select at least one group.")
        @Size(max = 100, message = "Select no more than 100 groups.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        List<@NotNull UUID> groupIds
) {
    public UpdateSourceGroupsRequest {
        groupIds = groupIds == null ? null : List.copyOf(groupIds);
    }

    public List<GroupId> toGroupIds() {
        return groupIds.stream().map(GroupId::new).toList();
    }
}
