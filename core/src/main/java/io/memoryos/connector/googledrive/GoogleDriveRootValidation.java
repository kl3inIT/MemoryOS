package io.memoryos.connector.googledrive;

import io.memoryos.connector.GoogleDriveGateway;
import io.memoryos.connector.GoogleDriveProviderException;
import java.util.regex.Pattern;

public final class GoogleDriveRootValidation {
    private static final Pattern FILE_ID = Pattern.compile("[A-Za-z0-9_-]{1,256}");

    private GoogleDriveRootValidation() {}

    public static GoogleDriveGateway.FileMetadata resolveMyDriveRoot(GoogleDriveGateway.Session session) {
        var root = session.metadata("root");
        requireMyDriveRoot(root);
        return root;
    }

    public static void requireMyDriveRoot(GoogleDriveGateway.FileMetadata root) {
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
