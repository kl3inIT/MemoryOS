package io.memoryos.connector;

import java.util.List;

/** Reads outbound HTTPS references from acquired content without provider or network access. */
public interface GoogleDriveLinkReader {
    List<Link> read(GoogleDriveProvider.AcquiredContent content);

    record Link(String url, String location) {}
}
