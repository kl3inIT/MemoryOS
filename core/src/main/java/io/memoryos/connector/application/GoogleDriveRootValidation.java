package io.memoryos.connector.application;

import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import java.util.regex.Pattern;

final class GoogleDriveRootValidation {
    private static final Pattern FILE_ID = Pattern.compile("[A-Za-z0-9_-]{1,256}");

    private GoogleDriveRootValidation() {}

    static GoogleDriveProvider.FileMetadata resolveMyDriveRoot(GoogleDriveProvider.Session session) {
        var root = session.metadata("root");
        requireMyDriveRoot(root);
        return root;
    }

    static void requireMyDriveRoot(GoogleDriveProvider.FileMetadata root) {
        if (root == null || root.id() == null || !FILE_ID.matcher(root.id()).matches() || "root".equals(root.id())
                || root.name() == null || root.name().isBlank() || root.name().length() > 255
                || root.version() == null || root.version().isBlank()) {
            throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.MALFORMED);
        }
        if (!root.folder() || root.trashed() || root.driveId() != null
                || root.shortcutTargetId() != null || !root.parents().isEmpty()) {
            throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.UNSUPPORTED);
        }
    }
}
