package io.memoryos.api.source.contract;

import io.memoryos.connector.GoogleDriveAclService;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "GoogleDriveAclFile", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GoogleDriveAclFileResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable GoogleDriveAclSnapshotResponse snapshot) {
    public static GoogleDriveAclFileResponse from(GoogleDriveAclService.File file) {
        return new GoogleDriveAclFileResponse(file.fileId(), file.name(),
                file.snapshot() == null ? null : GoogleDriveAclSnapshotResponse.from(file.snapshot()));
    }
}
