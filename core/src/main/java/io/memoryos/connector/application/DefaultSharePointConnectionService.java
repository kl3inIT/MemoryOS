package io.memoryos.connector.application;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointConnectionService;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.persistence.JdbcSharePointCredentialRepository;
import io.memoryos.connector.persistence.JdbcSharePointSourceRepository;
import io.memoryos.iam.tenant.TenantId;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DefaultSharePointConnectionService implements SharePointConnectionService {
    private final JdbcSharePointCredentialRepository credentials;
    private final JdbcSharePointSourceRepository sources;
    private final SharePointProvider provider;
    private final TransactionTemplate transactions;

    public DefaultSharePointConnectionService(JdbcSharePointCredentialRepository credentials,
            JdbcSharePointSourceRepository sources, SharePointProvider provider,
            PlatformTransactionManager transactionManager) {
        this.credentials = credentials;
        this.sources = sources;
        this.provider = provider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional(readOnly = true)
    public State state(TenantId tenantId, SourceId sourceId) {
        var credentialId = sources.credentialId(tenantId, sourceId);
        var stored = credentials.readUsable(tenantId, credentialId);
        return new State(credentialId, stored.name(), stored.status(), stored.revision(), stored.tenantHost());
    }

    @Override
    public Connection open(TenantId tenantId, SourceId sourceId) {
        return openCredential(tenantId, Objects.requireNonNull(
                transactions.execute(_ -> sources.credentialId(tenantId, sourceId))));
    }

    @Override
    public Connection openCredential(TenantId tenantId, CredentialId credentialId) {
        var stored = Objects.requireNonNull(transactions.execute(_ -> credentials.readUsable(tenantId, credentialId)));
        SharePointProvider.Session session;
        try (var authentication = credentials.authentication(tenantId, stored)) {
            try (var credential = authentication.credential(stored.cloud(), stored.directoryId().toString(),
                    stored.clientId().toString())) {
                session = provider.open(credential);
            }
        } catch (SharePointProviderException exception) {
            if (exception.failure() == SharePointProviderException.Failure.AUTHENTICATION) {
                authenticationFailed(tenantId, credentialId, stored.revision());
            }
            throw exception;
        }
        return new Connection(session, stored.revision(), stored.tenantHost());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean current(TenantId tenantId, SourceId sourceId, long credentialRevision) {
        try {
            var credentialId = sources.credentialId(tenantId, sourceId);
            return credentials.lock(tenantId, credentialId)
                    .filter(row -> row.usable() && row.revision() == credentialRevision)
                    .isPresent();
        } catch (SourceException exception) {
            if ("SOURCE_NOT_FOUND".equals(exception.code())) return false;
            throw exception;
        }
    }

    @Override
    public void authenticationFailed(TenantId tenantId, CredentialId credentialId, long credentialRevision) {
        transactions.executeWithoutResult(_ -> credentials.markNeedsUpdate(tenantId, credentialId, credentialRevision));
    }
}
