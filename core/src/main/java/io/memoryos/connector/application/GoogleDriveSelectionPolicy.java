package io.memoryos.connector.application;

import io.memoryos.connector.GoogleDriveSourceService.SelectionPolicy;
import io.memoryos.connector.SourceException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class GoogleDriveSelectionPolicy {
    public static final int ABSOLUTE_REQUEST_BYTES = 4 * 1024 * 1024;
    private final SelectionPolicy value;

    public GoogleDriveSelectionPolicy(
            @Value("${memoryos.google-drive.selection.max-explicit-roots-per-source:1000}") int maxRoots,
            @Value("${memoryos.google-drive.selection.max-request-bytes:3145728}") int maxBytes) {
        if (maxRoots < 1 || maxRoots > 10000 || maxBytes < 1024 || maxBytes > ABSOLUTE_REQUEST_BYTES)
            throw new IllegalArgumentException("Google selection policy exceeds finite deployment bounds");
        value = new SelectionPolicy(maxRoots, maxBytes, 500);
    }

    public SelectionPolicy value() { return value; }

    public void requireSize(String name, List<String> links, List<String> approvals) {
        long bytes = name.getBytes(StandardCharsets.UTF_8).length + 512L;
        for (String link : links) bytes += link.getBytes(StandardCharsets.UTF_8).length + 4L;
        for (String id : approvals) bytes += id.getBytes(StandardCharsets.UTF_8).length + 4L;
        if (bytes > value.maxRequestBytes())
            throw SourceException.invalid("The selection request exceeds the configured byte limit.", "selection byte budget exceeded");
    }
}
