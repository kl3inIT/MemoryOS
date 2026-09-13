package io.memoryos.api.source.contract;

import io.memoryos.connector.GoogleDriveAclSnapshot;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.document.DocumentId;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "GoogleDriveAclSnapshot", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GoogleDriveAclSnapshotResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID tenantId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Permission> permissions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) GoogleDriveAclSnapshot.Status status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Observation lastAttempt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Observation lastSuccess,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String errorCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String errorMessage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) GoogleDriveAclSnapshot.ContextStatus contextStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CurrentContext currentContext,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable UUID sourceItemId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> documentIds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant readAt) {
    static GoogleDriveAclSnapshotResponse from(GoogleDriveAclSnapshot value) {
        return new GoogleDriveAclSnapshotResponse(value.tenantId().value(), value.sourceId().value(), value.fileId(), value.revision(),
                value.permissions().stream().map(Permission::from).toList(), value.status(), Observation.from(value.lastAttempt()),
                value.lastSuccess() == null ? null : Observation.from(value.lastSuccess()), value.errorCode(), value.errorMessage(), value.contextStatus(),
                CurrentContext.from(value.currentContext()), value.sourceItemId() == null ? null : value.sourceItemId().value(),
                value.documentIds().stream().map(DocumentId::value).toList(), value.readAt());
    }

    @Schema(name = "GoogleDriveAclObservation", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Observation(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant at,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID operationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID credentialId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long credentialRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long scopeRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long generation) {
        static Observation from(GoogleDriveAclSnapshot.Observation value) {
            return new Observation(value.at(), value.operationId().value(), value.credentialId().value(),
                    value.credentialRevision(), value.scopeRevision(), value.generation());
        }
    }

    @Schema(name = "GoogleDriveAclCurrentContext", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CurrentContext(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean tenantActive,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean sourceActive,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID credentialId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long credentialRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean credentialActive,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long scopeRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long generation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Long membershipGeneration,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean selected,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean itemRemoved) {
        static CurrentContext from(GoogleDriveAclSnapshot.CurrentContext value) {
            return new CurrentContext(value.tenantActive(), value.sourceActive(), value.credentialId().value(),
                    value.credentialRevision(), value.credentialActive(), value.scopeRevision(), value.generation(),
                    value.membershipGeneration(), value.selected(), value.itemRemoved());
        }
    }

    @Schema(name = "GoogleDrivePermission", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Permission(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String role,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String emailAddress,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String domain,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant expirationTime,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Boolean allowFileDiscovery,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Boolean deleted,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Boolean pendingOwner,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PermissionDetail> permissionDetails,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String view,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Boolean inheritedPermissionsDisabled) {
        static Permission from(GoogleDriveProvider.Permission value) {
            return new Permission(value.id(), value.type(), value.role(), value.emailAddress(), value.domain(), value.expirationTime(),
                    value.allowFileDiscovery(), value.deleted(), value.pendingOwner(),
                    value.permissionDetails().stream().map(PermissionDetail::from).toList(), value.view(), value.inheritedPermissionsDisabled());
        }
    }

    @Schema(name = "GoogleDrivePermissionDetail", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PermissionDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String permissionType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String role,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String inheritedFrom,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Boolean inherited) {
        static PermissionDetail from(GoogleDriveProvider.PermissionDetail value) {
            return new PermissionDetail(value.permissionType(), value.role(), value.inheritedFrom(), value.inherited());
        }
    }
}
