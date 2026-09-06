package io.memoryos.api.source.contract;

import io.memoryos.iam.GroupId;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

@Schema(name = "CreateFileSourceRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateFileSourceRequest(
        @NotBlank(message = "Enter a source name.")
        @Size(max = 120, message = "Source name must not exceed 120 characters.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 120)
        String name,
        @Size(max = 100, message = "Select no more than 100 groups.")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @Nullable List<@NotNull UUID> groupIds
) {
    public CreateFileSourceRequest {
        groupIds = groupIds == null ? null : List.copyOf(groupIds);
    }

    public List<GroupId> toGroupIds() {
        return groupIds == null ? List.of() : groupIds.stream().map(GroupId::new).toList();
    }
}
