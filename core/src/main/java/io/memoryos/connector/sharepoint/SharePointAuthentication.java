package io.memoryos.connector.sharepoint;

import io.memoryos.connector.SharePointException;
import io.memoryos.connector.SharePointProvider;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Validated authentication material for an Entra application: either a client secret or a certificate.
 * The same instance is used to verify the credential with Microsoft and to store it.
 */
public record SharePointAuthentication(SharePointProvider.AuthMethod authMethod, byte @Nullable [] clientSecret,
                                       @Nullable SharePointCertificate certificate) implements AutoCloseable {

    public static final int MAX_SECRET_CHARS = 256;

    public SharePointAuthentication {
        Objects.requireNonNull(authMethod, "authMethod");
        clientSecret = clientSecret == null ? null : clientSecret.clone();
        if (authMethod == SharePointProvider.AuthMethod.CLIENT_SECRET
                ? clientSecret == null || certificate != null
                : clientSecret != null || certificate == null) {
            throw new IllegalArgumentException("SharePoint authentication carries exactly one payload");
        }
    }

    public static SharePointAuthentication clientSecret(byte[] secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length == 0 || secret.length > MAX_SECRET_CHARS * 4) throw SharePointException.invalidSecret();
        return new SharePointAuthentication(SharePointProvider.AuthMethod.CLIENT_SECRET, secret, null);
    }

    public static SharePointAuthentication certificate(SharePointCertificate certificate) {
        return new SharePointAuthentication(SharePointProvider.AuthMethod.CERTIFICATE, null,
                Objects.requireNonNull(certificate, "certificate"));
    }

    public SharePointProvider.Credential credential(SharePointProvider.Cloud cloud, String directoryId, String clientId) {
        if (authMethod == SharePointProvider.AuthMethod.CLIENT_SECRET) {
            return SharePointProvider.Credential.clientSecret(cloud, directoryId, clientId, clientSecret());
        }
        var material = Objects.requireNonNull(certificate);
        return SharePointProvider.Credential.certificate(cloud, directoryId, clientId,
                material.privateKey(), material.certificate());
    }

    @Override public byte @Nullable [] clientSecret() { return clientSecret == null ? null : clientSecret.clone(); }

    @Override public void close() {
        if (clientSecret != null) Arrays.fill(clientSecret, (byte) 0);
        if (certificate != null) certificate.close();
    }

    @Override public @NonNull String toString() { return "SharePointAuthentication[" + authMethod + "]"; }
}
