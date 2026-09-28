package io.memoryos.api.groups.contract;

import io.memoryos.iam.GroupPermissions;
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
        Permissions permissions
) {
    public GroupSummaryResponse {
        capabilities = List.copyOf(capabilities);
    }

    /** Affordance hints projected from the Group write guards; mutations keep their own checks. */
    @Schema(name = "GroupPermissions", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Permissions(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean manage,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean manageMembers,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean delete,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean editPermissions,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean manageSources
    ) {
        static Permissions from(GroupPermissions permissions) {
            return new Permissions(permissions.manage(), permissions.manageMembers(), permissions.delete(),
                    permissions.editPermissions(), permissions.manageSources());
        }
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
                Permissions.from(group.permissions())
        );
    }
}
