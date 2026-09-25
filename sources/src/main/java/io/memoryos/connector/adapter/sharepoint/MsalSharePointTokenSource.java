package io.memoryos.connector.adapter.sharepoint;

import static io.memoryos.connector.SharePointProviderException.Failure.AUTHENTICATION;
import static io.memoryos.connector.SharePointProviderException.Failure.MALFORMED;
import static io.memoryos.connector.SharePointProviderException.Failure.UNAVAILABLE;

import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IClientCredential;
import com.microsoft.aad.msal4j.MsalException;
import com.microsoft.aad.msal4j.MsalServiceException;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import java.net.URI;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-credentials tokens for an Entra application. One token lives in one provider session; msal4j's
 * cache belongs to the application instance built here and is never shared between Tenants.
 */
final class MsalSharePointTokenSource implements SharePointTokenSource {
    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";
    private static final Pattern AADSTS = Pattern.compile("AADSTS(\\d{4,7})");

    private final SharePointProviderProperties properties;
    private final ExecutorService executor;

    MsalSharePointTokenSource(SharePointProviderProperties properties, ExecutorService executor) {
        this.properties = properties;
        this.executor = executor;
    }

    @Override public String token(SharePointProvider.Credential credential) {
        IClientCredential secret = clientCredential(credential);
        try {
            var application = ConfidentialClientApplication.builder(credential.clientId(), secret)
                    .authority(authority(credential.directoryId()))
                    .executorService(executor)
                    // The authority is pinned by configuration, so no instance discovery round trip is needed.
                    .instanceDiscovery(false)
                    .connectTimeoutForDefaultHttpClient(Math.toIntExact(properties.connectTimeout().toMillis()))
                    .readTimeoutForDefaultHttpClient(Math.toIntExact(properties.requestTimeout().toMillis()))
                    .build();
            var parameters = ClientCredentialParameters.builder(Set.of(GRAPH_SCOPE)).build();
            var result = application.acquireToken(parameters)
                    .get(properties.acquisitionTimeout().toMillis(), TimeUnit.MILLISECONDS);
            String token = result.accessToken();
            if (token == null || token.isBlank()) throw new SharePointProviderException(MALFORMED);
            return token;
        } catch (java.net.MalformedURLException exception) {
            throw new SharePointProviderException(MALFORMED);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SharePointProviderException(UNAVAILABLE);
        } catch (TimeoutException exception) {
            throw new SharePointProviderException(UNAVAILABLE);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw failure(exception.getCause());
        } catch (MsalException exception) {
            throw failure(exception);
        }
    }

    private String authority(String directoryId) {
        URI base = properties.authority();
        String path = base.getPath() == null || base.getPath().isEmpty() ? "/" : base.getPath();
        if (!path.endsWith("/")) path = path + "/";
        return base.resolve(path + directoryId + "/").toString();
    }

    private IClientCredential clientCredential(SharePointProvider.Credential credential) {
        if (credential.authMethod() == SharePointProvider.AuthMethod.CLIENT_SECRET) {
            byte[] secret = java.util.Objects.requireNonNull(credential.clientSecret());
            try {
                return ClientCredentialFactory.createFromSecret(new String(secret, java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        }
        byte[] key = java.util.Objects.requireNonNull(credential.privateKey());
        try {
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));
            var certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(
                            java.util.Objects.requireNonNull(credential.certificate())));
            return ClientCredentialFactory.createFromCertificate(privateKey, certificate);
        } catch (java.security.GeneralSecurityException exception) {
            throw new SharePointProviderException(MALFORMED);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static SharePointProviderException failure(Throwable cause) {
        if (cause instanceof MsalServiceException service) {
            return new SharePointProviderException(AUTHENTICATION, classify(service.getMessage()));
        }
        if (cause instanceof MsalException) {
            return new SharePointProviderException(AUTHENTICATION, SharePointProviderException.Reason.UNCLASSIFIED);
        }
        return new SharePointProviderException(UNAVAILABLE);
    }

    /** Maps the Entra error number so the application layer never echoes Microsoft's own text. */
    static SharePointProviderException.Reason classify(String message) {
        if (message == null) return SharePointProviderException.Reason.UNCLASSIFIED;
        Matcher matcher = AADSTS.matcher(message.toUpperCase(Locale.ROOT));
        if (!matcher.find()) return SharePointProviderException.Reason.UNCLASSIFIED;
        return switch (matcher.group(1)) {
            case "7000215", "7000216" -> SharePointProviderException.Reason.INVALID_CLIENT_SECRET;
            case "7000222" -> SharePointProviderException.Reason.EXPIRED_CLIENT_SECRET;
            case "700027", "700024", "700025" -> SharePointProviderException.Reason.CERTIFICATE_NOT_REGISTERED;
            case "700016" -> SharePointProviderException.Reason.APPLICATION_NOT_FOUND;
            case "900021", "90002", "900023" -> SharePointProviderException.Reason.DIRECTORY_NOT_FOUND;
            case "65001", "500011", "501051" -> SharePointProviderException.Reason.CONSENT_REQUIRED;
            default -> SharePointProviderException.Reason.UNCLASSIFIED;
        };
    }
}
