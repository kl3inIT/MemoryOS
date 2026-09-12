package io.memoryos.chat.application;

import io.memoryos.chat.ChatException;
import io.memoryos.objectstorage.ObjectUploadPurpose;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("memoryos.chat.files")
public record ChatFileProperties(@DefaultValue("104857600") long maxSizeBytes,
        @DefaultValue("262144000") long deploymentCeilingBytes) {
    public ChatFileProperties {
        if (maxSizeBytes < 1 || maxSizeBytes > deploymentCeilingBytes
                || deploymentCeilingBytes > ObjectUploadPurpose.CHAT_FILE.maximumBytes()) {
            throw new IllegalArgumentException("Chat file limit must be positive and not exceed deployment ceiling or 250 MiB");
        }
    }

    public void validateSize(long size) {
        if (size < 1 || size > maxSizeBytes) throw ChatException.invalid("File exceeds the configured Chat upload limit.");
    }
}
