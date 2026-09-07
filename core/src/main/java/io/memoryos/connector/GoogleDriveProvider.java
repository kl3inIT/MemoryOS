package io.memoryos.connector;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public interface GoogleDriveProvider {
    Session open(Credential credential);

    interface Session extends AutoCloseable {
        FilePage listFiles(String parentId, @Nullable String pageToken);
        FileMetadata metadata(String fileId);
        AcquiredContent acquire(FileMetadata file);
        @Nullable byte[] rotatedRefreshToken();
        @Override void close();
    }

    record Credential(String clientId, byte[] clientSecret, byte[] refreshToken) implements AutoCloseable {
        public Credential {
            Objects.requireNonNull(clientId, "clientId");
            clientSecret = Objects.requireNonNull(clientSecret, "clientSecret").clone();
            refreshToken = Objects.requireNonNull(refreshToken, "refreshToken").clone();
        }
        @Override public byte[] clientSecret() { return clientSecret.clone(); }
        @Override public byte[] refreshToken() { return refreshToken.clone(); }
        @Override public void close() { Arrays.fill(clientSecret, (byte) 0); Arrays.fill(refreshToken, (byte) 0); }
        @Override public String toString() { return "Credential[redacted]"; }
    }

    record FilePage(List<FileMetadata> files, @Nullable String nextPageToken) {
        public FilePage { files = List.copyOf(files); }
    }


    record FileMetadata(String id, String name, String mimeType, String version,
                        @Nullable String checksum, @Nullable Instant modifiedAt,
                        boolean trashed, List<String> parents, @Nullable String driveId,
                        @Nullable String shortcutTargetId) {
        public FileMetadata { parents = List.copyOf(parents); }
        public boolean folder() { return "application/vnd.google-apps.folder".equals(mimeType); }
    }

    record AcquiredContent(String filename, String mediaType, byte[] bytes,
                           SourceInputDescriptor descriptor) {
        public AcquiredContent {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(descriptor, "descriptor");
        }
        @Override public String toString() { return "AcquiredContent[" + bytes.length + " bytes]"; }
    }
}
