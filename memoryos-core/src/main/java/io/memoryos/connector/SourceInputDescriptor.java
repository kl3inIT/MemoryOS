package io.memoryos.connector;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record SourceInputDescriptor(
        SourceInputFormat format,
        @Nullable String providerFileId,
        @Nullable String providerVersion,
        @Nullable String sourceUrl
) {
    public SourceInputDescriptor {
        Objects.requireNonNull(format, "format");
    }

    public static SourceInputDescriptor binary() {
        return new SourceInputDescriptor(SourceInputFormat.BINARY, null, null, null);
    }
}
