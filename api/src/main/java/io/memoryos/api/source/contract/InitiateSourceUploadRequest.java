package io.memoryos.api.source.contract;

import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "InitiateSourceUploadRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record InitiateSourceUploadRequest(
        @NotBlank
        @Size(max = 255)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 255)
        String filename,
        @NotBlank
        @Size(max = 160)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 160)
        String mediaType,
        @Min(1)
        @Max(ObjectUploadSpecification.MAX_SIZE_BYTES)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "10485760")
        long sizeBytes,
        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{64}$")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$")
        String sha256
) {
    public ObjectUploadSpecification toSpecification() {
        return new ObjectUploadSpecification(filename, mediaType, sizeBytes, new ContentSha256(sha256));
    }
}
