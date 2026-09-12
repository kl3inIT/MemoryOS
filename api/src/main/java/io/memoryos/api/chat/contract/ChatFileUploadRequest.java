package io.memoryos.api.chat.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ChatFileUploadRequest(@NotNull UUID requestId, @NotBlank @Size(max=255) String filename,
        @NotBlank @Size(max=160) String mediaType, @Positive long sizeBytes,
        @NotNull @Pattern(regexp="[0-9a-f]{64}") String sha256) {}
