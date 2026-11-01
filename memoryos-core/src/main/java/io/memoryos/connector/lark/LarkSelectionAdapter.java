package io.memoryos.connector.lark;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.LarkSourceService.RootKind;
import io.memoryos.connector.LarkSourceService.RootView;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceSelectionProcessor.Result;
import io.memoryos.connector.SourceSelectionProcessor.Work;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.lark.persistence.JdbcLarkSelectionRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSelectionRepository.Entry;
import io.memoryos.connector.lark.persistence.JdbcLarkSelectionRepository.Intent;
import io.memoryos.connector.sync.BatchedSelectionAdapter;
import java.util.ArrayList;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Reads every root of an accepted scope request with the credential's app: a Wiki space the app is a member of, a
 * Drive folder shared with it. A root the app cannot read fails the request, so a Source never starts on a space or
 * a folder it will find empty.
 */
@Service
public class LarkSelectionAdapter extends BatchedSelectionAdapter {
    private final JdbcLarkSelectionRepository selections;
    private final DefaultLarkSourceService sources;
    private final LarkConnectionService connections;

    public LarkSelectionAdapter(JdbcLarkSelectionRepository selections, DefaultLarkSourceService sources,
            LarkConnectionService connections, PlatformTransactionManager transactionManager) {
        super(selections.operations(), transactionManager);
        this.selections = selections;
        this.sources = sources;
        this.connections = connections;
    }

    @Override
    public SourceType type() {
        return SourceType.LARK;
    }

    @Override
    protected Result verify(Work work, long started) {
        var intent = selections.intent(work);
        try {
            transactions.executeWithoutResult(_ -> sources.requireIntent(work, intent));
            try (var connection = connections.openCredential(work.tenantId(),
                    new CredentialId(Objects.requireNonNull(intent.credentialId())))) {
                if (connection.credentialRevision() != intent.credentialRevision()) throw staleCredential();
                verify(work, intent, connection.session(), started);
            }
            var roots = new ArrayList<RootView>();
            for (Entry entry : selections.entries(work)) {
                if (!entry.verified()) throw new ContinueBatch();
                roots.add(new RootView(entry.kind(), entry.token(), entry.displayName()));
            }
            transactions.executeWithoutResult(_ -> sources.activate(work, intent, roots));
            return Result.COMPLETED;
        } catch (LarkProviderException exception) {
            return providerFailure(work, intent, exception, started);
        }
    }

    private Result providerFailure(Work work, Intent intent, LarkProviderException exception, long started) {
        String code = "SOURCE_LARK_" + exception.failure().name();
        if (exception.failure() == LarkProviderException.Failure.AUTHENTICATION && intent.credentialId() != null)
            connections.authenticationFailed(work.tenantId(), new CredentialId(intent.credentialId()),
                    intent.credentialRevision());
        boolean retryable = switch (exception.failure()) {
            case QUOTA, UNAVAILABLE -> true;
            default -> false;
        };
        return retryable ? continueLater(work, started, code) : finish(work, Result.FAILED, code);
    }

    /** Reads the roots that are still unverified, checkpointing each one as it succeeds. */
    private void verify(Work work, Intent intent, LarkGateway.Session session, long started) {
        for (Entry entry : selections.entries(work)) {
            if (entry.verified()) continue;
            requireBatchTime(started);
            transactions.executeWithoutResult(_ -> {
                sources.requireIntent(work, intent);
                selections.reserveRequest(work);
            });
            String name = entry.kind() == RootKind.WIKI_SPACE
                    ? session.space(entry.token()).name() : session.folder(entry.token()).name();
            transactions.executeWithoutResult(_ -> {
                sources.requireIntent(work, intent);
                selections.verified(work, entry, name);
            });
        }
    }

    private static SourceException staleCredential() {
        return SourceException.conflict("Lark credential changed while verifying the scope");
    }
}
