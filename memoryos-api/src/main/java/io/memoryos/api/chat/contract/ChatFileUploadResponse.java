package io.memoryos.api.chat.contract;

import io.memoryos.objectstorage.UploadAuthorization;
import org.jspecify.annotations.Nullable;

public record ChatFileUploadResponse(ChatFileResponse file, @Nullable UploadAuthorization upload) {}
