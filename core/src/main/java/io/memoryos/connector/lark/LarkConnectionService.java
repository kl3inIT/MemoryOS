package io.memoryos.connector.lark;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.lark.persistence.JdbcLarkCredentialRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSourceRepository;
import io.memoryos.shared.TenantId;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Opens a provider session from a stored Lark credential and reports the revision it was opened at. */
@Service
public class LarkConnectionService {
    public record State(CredentialId credentialId, String name, String status, long credentialRevision) {}

    public record Connection(LarkGateway.Session session, long credentialRevision, LarkGateway.Region region)
            implements AutoCloseable {
        @Override public void close() { session.close(); }
    }

    private final JdbcLarkCredentialRepository credentials;
    private final JdbcLarkSourceRepository sources;
    private final LarkGateway provider;
    private final TransactionTemplate transactions;

    public LarkConnectionService(JdbcLarkCredentialRepository credentials, JdbcLarkSourceRepository sources,
            LarkGateway provider, PlatformTransactionManager transactionManager) {
        this.credentials = credentials;
        this.sources = sources;
        this.provider = provider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public State state(TenantId tenantId, SourceId sourceId) {
        var credentialId = sources.credentialId(tenantId, sourceId);
        var stored = credentials.readUsable(tenantId, credentialId);
        return new State(credentialId, stored.name(), stored.status(), stored.revision());
    }

    public Connection open(TenantId tenantId, SourceId sourceId) {
        return openCredential(tenantId, Objects.requireNonNull(
                transactions.execute(_ -> sources.credentialId(tenantId, sourceId))));
    }

    public Connection openCredential(TenantId tenantId, CredentialId credentialId) {
        var stored = Objects.requireNonNull(transactions.execute(_ -> credentials.readUsable(tenantId, credentialId)));
        LarkGateway.Session session;
        try (var credential = credentials.credential(tenantId, stored)) {
            session = provider.open(credential);
        } catch (LarkProviderException exception) {
            if (exception.failure() == LarkProviderException.Failure.AUTHENTICATION)
                authenticationFailed(tenantId, credentialId, stored.revision());
            throw exception;
        }
        return new Connection(session, stored.revision(), stored.region());
    }

    /** True only while the Source still owns the usable credential revision captured by a version. */
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

    /** Marks the credential as needing an update after Lark rejected it. */
    public void authenticationFailed(TenantId tenantId, CredentialId credentialId, long credentialRevision) {
        transactions.executeWithoutResult(_ -> credentials.markNeedsUpdate(tenantId, credentialId, credentialRevision));
    }
}
