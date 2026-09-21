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
        List<Permission> permissions(String fileId);
        AcquiredContent acquire(FileMetadata file);
        @Nullable byte[] rotatedRefreshToken();
        @Override void close();
    }

    /** The scopes a service account's domain-wide delegation must grant; OAuth grants carry their own. */
    List<String> SERVICE_ACCOUNT_SCOPES = List.of(
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/documents.readonly",
            "https://www.googleapis.com/auth/spreadsheets.readonly",
            "https://www.googleapis.com/auth/admin.directory.user.readonly",
            "https://www.googleapis.com/auth/admin.directory.group.readonly");

    sealed interface Credential extends AutoCloseable permits OAuthCredential, ServiceAccountCredential {
        @Override void close();
    }

    record OAuthCredential(String clientId, byte[] clientSecret, byte[] refreshToken) implements Credential {
        public OAuthCredential {
            Objects.requireNonNull(clientId, "clientId");
            clientSecret = Objects.requireNonNull(clientSecret, "clientSecret").clone();
            refreshToken = Objects.requireNonNull(refreshToken, "refreshToken").clone();
        }
        @Override public byte[] clientSecret() { return clientSecret.clone(); }
        @Override public byte[] refreshToken() { return refreshToken.clone(); }
        @Override public void close() { Arrays.fill(clientSecret, (byte) 0); Arrays.fill(refreshToken, (byte) 0); }
        @Override public String toString() { return "OAuthCredential[redacted]"; }
    }

    /** A domain-wide-delegated service account acting as {@code subject}, a user of its Workspace. */
    record ServiceAccountCredential(GoogleDriveServiceAccountKey key, String subject) implements Credential {
        public ServiceAccountCredential {
            Objects.requireNonNull(key, "key");
            if (Objects.requireNonNull(subject, "subject").isBlank()) throw new IllegalArgumentException("subject must not be blank");
        }
        @Override public void close() { key.close(); }
        @Override public String toString() { return "ServiceAccountCredential[redacted]"; }
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

    record Permission(String id, String type, String role, @Nullable String emailAddress,
                      @Nullable String domain, @Nullable Instant expirationTime,
                      @Nullable Boolean allowFileDiscovery, @Nullable Boolean deleted,
                      @Nullable Boolean pendingOwner, List<PermissionDetail> permissionDetails,
                      @Nullable String view, @Nullable Boolean inheritedPermissionsDisabled) {
        public Permission {
            if (Objects.requireNonNull(id, "id").isBlank()
                    || Objects.requireNonNull(type, "type").isBlank()
                    || Objects.requireNonNull(role, "role").isBlank()) {
                throw new IllegalArgumentException("Permission identity, type and role must not be blank");
            }
            permissionDetails = List.copyOf(permissionDetails);
        }
        @Override public String toString() { return "Permission[redacted]"; }
    }

    record PermissionDetail(@Nullable String permissionType, @Nullable String role,
                            @Nullable String inheritedFrom, @Nullable Boolean inherited) {
        @Override public String toString() { return "PermissionDetail[redacted]"; }
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
