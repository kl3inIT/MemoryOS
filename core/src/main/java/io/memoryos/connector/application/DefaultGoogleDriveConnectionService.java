package io.memoryos.connector.application;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveConnectionService;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.persistence.JdbcGoogleDriveCredentialRepository;
import io.memoryos.tenant.TenantId;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DefaultGoogleDriveConnectionService implements GoogleDriveConnectionService {
    private final JdbcGoogleDriveCredentialRepository credentials;
    private final GoogleDriveProvider provider;
    private final TransactionTemplate transactions;

    public DefaultGoogleDriveConnectionService(JdbcGoogleDriveCredentialRepository credentials, GoogleDriveProvider provider,
            PlatformTransactionManager transactionManager) {
        this.credentials = credentials;
        this.provider = provider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional(readOnly = true)
    public State state(TenantId tenantId, SourceId sourceId) { return credentials.state(tenantId, sourceId); }

    @Override
    public Connection open(TenantId tenantId, SourceId sourceId) {
        var connection = openCredential(tenantId, credentials.credentialId(tenantId, sourceId));
        try {
            if (!Boolean.TRUE.equals(transactions.execute(_ -> current(tenantId, sourceId, connection.credentialRevision())))) {
                throw SourceException.conflict("Google connection lost Source authority");
            }
            return connection;
        } catch (RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    @Override
    public Connection openCredential(TenantId tenantId, CredentialId credentialId) {
        var stored = credentials.readUsable(tenantId, credentialId);
        byte[] token = credentials.decrypt(tenantId, stored);
        byte[] secret = null;
        GoogleDriveProvider.Session session;
        try (var client = credentials.oauthClient(tenantId, stored)) {
            secret = client.clientSecret();
            try (var grant = new GoogleDriveProvider.Credential(client.clientId(), secret, token)) {
                session = provider.open(grant);
            }
        } catch (GoogleDriveProviderException exception) {
            if (exception.failure() == GoogleDriveProviderException.Failure.AUTHENTICATION) {
                boolean invalidated = Boolean.TRUE.equals(transactions.execute(_ ->
                        credentials.refreshFailed(tenantId, credentialId, stored.revision(), stored.payloadRevision())));
                if (!invalidated) throw SourceException.conflict("Google refresh lost credential authority");
            }
            throw exception;
        } finally {
            Arrays.fill(token, (byte) 0);
            if (secret != null) Arrays.fill(secret, (byte) 0);
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
            } else if (!Boolean.TRUE.equals(transactions.execute(_ -> currentCredential(tenantId, credentialId, stored.revision())))) {
                throw SourceException.conflict("Google connection lost credential authority");
            }
            return new Connection(session, stored.revision());
        } catch (RuntimeException exception) {
            session.close();
            throw exception;
        } finally { if (rotated != null) Arrays.fill(rotated, (byte) 0); }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean current(TenantId tenantId, SourceId sourceId, long credentialRevision) {
        return credentials.lockSource(tenantId, sourceId)
                .filter(row -> row.usable() && row.revision() == credentialRevision).isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean currentCredential(TenantId tenantId, CredentialId credentialId, long credentialRevision) {
        return credentials.lock(tenantId, credentialId)
                .filter(row -> row.usable() && row.revision() == credentialRevision).isPresent();
    }

    @Override
    public void authenticationFailed(TenantId tenantId, SourceId sourceId, long credentialRevision) {
        transactions.executeWithoutResult(_ -> {
            var stored = credentials.lockSource(tenantId, sourceId);
            stored.ifPresent(row -> credentials.authenticationFailed(tenantId, new CredentialId(row.credentialId()), credentialRevision));
        });
    }

    @Override
    public void authenticationFailedCredential(TenantId tenantId, CredentialId credentialId, long credentialRevision) {
        transactions.executeWithoutResult(_ -> credentials.authenticationFailed(tenantId, credentialId, credentialRevision));
    }
}
