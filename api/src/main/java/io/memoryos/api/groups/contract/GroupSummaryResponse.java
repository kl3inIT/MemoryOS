package io.memoryos.api.groups.contract;

import io.memoryos.iam.GroupAction;
import io.memoryos.iam.GroupSummary;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.IamCapability;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

@Schema(name = "GroupSummary", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GroupSummaryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, enumAsRef = true)
        @Nullable GroupSystemKey systemKey,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long memberCount,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long managerCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<IamCapability> capabilities,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> actions
) {
    public GroupSummaryResponse {
        capabilities = List.copyOf(capabilities);
        actions = List.copyOf(actions);
    }

    public static GroupSummaryResponse from(GroupSummary group) {
        return new GroupSummaryResponse(
                group.id().value(),
                group.name(),
                group.systemKey(),
                group.memberCount(),
                group.managerCount(),
                group.capabilities().stream()
                        .sorted(Comparator.comparingInt(IamCapability::ordinal))
                        .toList(),
                group.actions().stream()
                        .sorted(Comparator.comparingInt(GroupAction::ordinal))
                        .map(GroupAction::token)
                        .toList()
        );
    }
}
