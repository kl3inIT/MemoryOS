package io.memoryos.connector.googledrive;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveCredentialRepository;
import io.memoryos.shared.TenantId;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GoogleDriveConnectionService {
    public record State(CredentialId credentialId, String accountEmail, String status, long credentialRevision, boolean oauthClientConfigured,
            String authMethod) {}
    public record Connection(GoogleDriveProvider.Session session, long credentialRevision) implements AutoCloseable {
        @Override public void close() { session.close(); }
    }

    private final JdbcGoogleDriveCredentialRepository credentials;
    private final GoogleDriveProvider provider;
    private final TransactionTemplate transactions;

    public GoogleDriveConnectionService(JdbcGoogleDriveCredentialRepository credentials, GoogleDriveProvider provider,
            PlatformTransactionManager transactionManager) {
        this.credentials = credentials;
        this.provider = provider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public State state(TenantId tenantId, SourceId sourceId) { return credentials.state(tenantId, sourceId); }

    public Connection open(TenantId tenantId, SourceId sourceId) {
        var connection = openCredential(tenantId, credentials.credentialId(tenantId, sourceId));
        try {
            if (!Boolean.TRUE.equals(transactions.execute(_ -> sourceCurrent(tenantId, sourceId, connection.credentialRevision())))) {
                throw SourceException.conflict("Google connection lost Source authority");
            }
            return connection;
        } catch (RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    public Connection openCredential(TenantId tenantId, CredentialId credentialId) {
        var stored = credentials.readUsable(tenantId, credentialId);
        GoogleDriveProvider.Session session;
        try {
            session = stored.serviceAccount() ? openServiceAccount(tenantId, stored) : openOAuth(tenantId, stored);
        } catch (GoogleDriveProviderException exception) {
            if (exception.failure() == GoogleDriveProviderException.Failure.AUTHENTICATION) {
                boolean invalidated = Boolean.TRUE.equals(transactions.execute(_ ->
                        credentials.refreshFailed(tenantId, credentialId, stored.revision(), stored.payloadRevision())));
                if (!invalidated) throw SourceException.conflict("Google refresh lost credential authority");
            }
            throw exception;
        }
        byte[] rotated = null;
        try {
            rotated = session.rotatedRefreshToken();
            if (rotated != null) {
                byte[] replacement = rotated;
                if (!Boolean.TRUE.equals(transactions.execute(_ -> credentials.rotate(tenantId, credentialId,
                        stored.revision(), stored.payloadRevision(), replacement)))) {
                    throw SourceException.conflict("Google refresh lost credential authority");
                }
            } else if (!Boolean.TRUE.equals(transactions.execute(_ -> credentialCurrent(tenantId, credentialId, stored.revision())))) {
                throw SourceException.conflict("Google connection lost credential authority");
            }
            return new Connection(session, stored.revision());
        } catch (RuntimeException exception) {
            session.close();
            throw exception;
        } finally { if (rotated != null) Arrays.fill(rotated, (byte) 0); }
    }

    private GoogleDriveProvider.Session openOAuth(TenantId tenantId, JdbcGoogleDriveCredentialRepository.Stored stored) {
        byte[] token = credentials.decrypt(tenantId, stored);
        byte[] secret = null;
        try (var client = credentials.oauthClient(tenantId, stored)) {
            secret = client.clientSecret();
            try (var grant = new GoogleDriveProvider.OAuthCredential(client.clientId(), secret, token)) {
                return provider.open(grant);
            }
        } finally {
            Arrays.fill(token, (byte) 0);
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    /** A service account acts as its primary admin, the account recorded on the credential. */
    private GoogleDriveProvider.Session openServiceAccount(TenantId tenantId, JdbcGoogleDriveCredentialRepository.Stored stored) {
        try (var grant = new GoogleDriveProvider.ServiceAccountCredential(credentials.serviceAccountKey(tenantId, stored), stored.email())) {
            return provider.open(grant);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean current(TenantId tenantId, SourceId sourceId, long credentialRevision) {
        return sourceCurrent(tenantId, sourceId, credentialRevision);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean currentCredential(TenantId tenantId, CredentialId credentialId, long credentialRevision) {
        return credentialCurrent(tenantId, credentialId, credentialRevision);
    }

    /** Locks the Source's credential row; the caller holds a transaction, its own template's or the proxy's. */
    private boolean sourceCurrent(TenantId tenantId, SourceId sourceId, long credentialRevision) {
        return credentials.lockSource(tenantId, sourceId)
                .filter(row -> row.usable() && row.revision() == credentialRevision).isPresent();
    }

    private boolean credentialCurrent(TenantId tenantId, CredentialId credentialId, long credentialRevision) {
        return credentials.lock(tenantId, credentialId)
                .filter(row -> row.usable() && row.revision() == credentialRevision).isPresent();
    }

    public void authenticationFailed(TenantId tenantId, SourceId sourceId, long credentialRevision) {
        transactions.executeWithoutResult(_ -> {
            var stored = credentials.lockSource(tenantId, sourceId);
            stored.ifPresent(row -> credentials.authenticationFailed(tenantId, new CredentialId(row.credentialId()), credentialRevision));
        });
    }

    public void authenticationFailedCredential(TenantId tenantId, CredentialId credentialId, long credentialRevision) {
        transactions.executeWithoutResult(_ -> credentials.authenticationFailed(tenantId, credentialId, credentialRevision));
    }
}
