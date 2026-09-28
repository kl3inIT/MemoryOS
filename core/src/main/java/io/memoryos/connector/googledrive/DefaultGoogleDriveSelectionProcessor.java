package io.memoryos.connector.googledrive;

import io.memoryos.connector.*;
import io.memoryos.connector.GoogleDriveSourceService.*;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSelectionRepository;
import io.memoryos.connector.sync.SelectionBatchProcessor;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSelectionRepository.Entry;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSelectionRepository.Intent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class DefaultGoogleDriveSelectionProcessor extends SelectionBatchProcessor implements GoogleDriveSelectionProcessor {
    /** Drive metadata requests one batch makes before handing the work back. */
    private static final int BATCH_REQUESTS = 32;

    private final JdbcGoogleDriveSelectionRepository selections;
    private final DefaultGoogleDriveSourceService sources;
    private final GoogleDriveConnectionService connections;

    public DefaultGoogleDriveSelectionProcessor(JdbcGoogleDriveSelectionRepository selections,
            DefaultGoogleDriveSourceService sources, GoogleDriveConnectionService connections,
            PlatformTransactionManager transactionManager) {
        super(selections.operations(), transactionManager);
        this.selections=selections; this.sources=sources; this.connections=connections;
    }

    @Override protected Result verify(Work work, long started) {
        var intent=selections.intent(work);
        try {
            transactions.executeWithoutResult(_ -> sources.requireIntent(work,intent));
            try (var connection=connections.openCredential(work.tenantId(),new CredentialId(Objects.requireNonNull(intent.credentialId())))) {
                if (connection.credentialRevision()!=intent.credentialRevision()) throw SourceException.staleConfiguration();
                transactions.executeWithoutResult(_ -> sources.requireIntent(work,intent));
                var batch=new Batch(work,intent,connection.session(),started);
                batch.verify();
            }
            var entries=selections.entries(work);
            var roots=new ArrayList<Root>();
            var approvals=new ArrayList<LinkedDocument>();
            for (var entry:entries) {
                if (!entry.verified()) throw new ContinueBatch();
                if (entry.kind().equals("ROOT")) {
                    String id=intent.scopeMode()==ScopeMode.GENERAL
                            ? selections.metadata(work,"root").orElseThrow().id():entry.id();
                    roots.add(new Root(id,Objects.requireNonNull(entry.name()),Objects.requireNonNull(entry.mimeType())));
                } else approvals.add(new LinkedDocument(entry.id(),Objects.requireNonNull(entry.name()),
                        Objects.requireNonNull(entry.mimeType()),true,entry.covered(),LinkedDocumentStatus.valueOf(entry.status()),List.of()));
            }
            transactions.executeWithoutResult(_ -> sources.activate(work,intent,roots,approvals));
            return Result.COMPLETED;
        } catch (GoogleDriveProviderException exception) {
            String code="SOURCE_GOOGLE_"+exception.failure().name();
            if (exception.requiresReconnect() && intent.credentialId()!=null)
                connections.authenticationFailedCredential(work.tenantId(),new CredentialId(intent.credentialId()),intent.credentialRevision());
            if (exception.failure()==GoogleDriveProviderException.Failure.QUOTA
                    || exception.failure()==GoogleDriveProviderException.Failure.UNAVAILABLE
                    || exception.failure()==GoogleDriveProviderException.Failure.INCONSISTENT) {
                return continueLater(work,started,code);
            }
            return finish(work,Result.FAILED,code);
        }
    }

    private final class Batch {
        private final Work work;
        private final Intent intent;
        private final GoogleDriveProvider.Session session;
        private final long started;
        private int calls;
        private final Set<String> roots;
        private final List<Entry> entries;

        Batch(Work work,Intent intent,GoogleDriveProvider.Session session,long started) {
            this.work=work; this.intent=intent; this.session=session; this.started=started;
            entries=selections.entries(work);
            roots=new HashSet<>();
            for (var entry:entries) if (entry.kind().equals("ROOT")) roots.add(entry.id());
        }

        void verify() {
            for (var entry:entries) {
                if (entry.verified()) continue;
                requireBatchTime(started);
                transactions.executeWithoutResult(_ -> sources.requireIntent(work,intent));
                try {
                    var file=metadata(entry.id());
                    if (entry.kind().equals("ROOT")) verifyRoot(entry,file);
                    else verifyApproval(entry,file);
                } catch (GoogleDriveProviderException exception) {
                    if (!entry.kind().equals("APPROVAL") || !entry.wasSelected()
                            || exception.failure()!=GoogleDriveProviderException.Failure.NOT_FOUND
                            && exception.failure()!=GoogleDriveProviderException.Failure.UNSUPPORTED) throw exception;
                    checkpoint(entry,Objects.requireNonNull(entry.name()),Objects.requireNonNull(entry.mimeType()),
                            roots.contains(entry.id()),LinkedDocumentStatus.UNAVAILABLE);
                }
            }
        }

        private void verifyRoot(Entry entry,GoogleDriveProvider.FileMetadata file) {
            if (intent.scopeMode()==ScopeMode.GENERAL) GoogleDriveRootValidation.requireMyDriveRoot(file);
            else {
                requireSupported(file);
                if (file.folder() && (file.id().equals(file.driveId()) || file.driveId()==null
                        && file.parents().isEmpty() && file.id().equals(metadata("root").id())))
                    throw SourceException.invalidRootLink("Link a specific folder, not an entire drive.");
                if (covered(entry,file,false)) throw SourceException.overlappingRoots();
            }
            checkpoint(entry,file.name(),file.mimeType(),false,LinkedDocumentStatus.AVAILABLE);
        }

        private void verifyApproval(Entry entry,GoogleDriveProvider.FileMetadata file) {
            boolean supported=GoogleDriveLinkedDiscovery.supported(file);
            if (!entry.wasSelected() && (!supported || file.trashed()))
                throw SourceException.invalid("Select only available linked documents.","linked document became unavailable");
            checkpoint(entry,file.name(),file.mimeType(),covered(entry,file,true),file.trashed()?LinkedDocumentStatus.UNAVAILABLE:
                    supported?LinkedDocumentStatus.AVAILABLE:LinkedDocumentStatus.UNSUPPORTED);
        }

        private boolean covered(Entry entry,GoogleDriveProvider.FileMetadata file,boolean includeSelf) {
            if (includeSelf && roots.contains(file.id())) return true;
            boolean known=true;
            for (String parent:file.parents()) {
                if (roots.contains(parent)) return true;
                var coverage=selections.ancestorCoverage(work,parent);
                if (coverage.isEmpty()) known=false;
                else if (coverage.get()) return true;
            }
            if (known) return false;
            transactions.executeWithoutResult(_ -> {
                sources.requireIntent(work,intent);
                selections.enqueueAncestors(work,entry,file.parents());
            });
            while (true) {
                requireBatchTime(started);
                var next=selections.nextAncestor(work,entry);
                if (next.isEmpty()) {
                    transactions.executeWithoutResult(_ -> {
                        sources.requireIntent(work,intent);
                        selections.uncoveredAncestors(work,entry);
                    });
                    return false;
                }
                String parent=next.get();
                if (roots.contains(parent)) return true;
                var coverage=selections.ancestorCoverage(work,parent);
                if (coverage.isPresent()) {
                    if (coverage.get()) return true;
                    transactions.executeWithoutResult(_ -> {
                        sources.requireIntent(work,intent);
                        selections.visitAncestor(work,entry,parent);
                    });
                    continue;
                }
                var ancestor=metadata(parent);
                requireSupported(ancestor);
                transactions.executeWithoutResult(_ -> {
                    sources.requireIntent(work,intent);
                    selections.enqueueAncestors(work,entry,ancestor.parents());
                    selections.visitAncestor(work,entry,parent);
                });
            }
        }

        private GoogleDriveProvider.FileMetadata metadata(String id) {
            transactions.executeWithoutResult(_ -> sources.requireIntent(work,intent));
            var cached=selections.metadata(work,id);
            if (cached.isPresent()) return cached.get();
            if (calls>=BATCH_REQUESTS) throw new ContinueBatch();
            requireBatchTime(started);
            transactions.executeWithoutResult(_ -> {
                sources.requireIntent(work,intent);
                selections.reserveRequest(work);
            });
            calls++;
            var file=session.metadata(id);
            if ((!id.equals("root") && !id.equals(file.id())) || file.name().length()>255 || file.mimeType().length()>160
                    || file.parents().size()>100 || file.parents().stream().anyMatch(parent -> !parent.matches("[A-Za-z0-9_-]{1,256}")))
                throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.MALFORMED);
            transactions.executeWithoutResult(_ -> {
                sources.requireIntent(work,intent);
                selections.cache(work,id,file);
            });
            return file;
        }

        private void checkpoint(Entry entry,String name,String mime,boolean covered,LinkedDocumentStatus status) {
            transactions.executeWithoutResult(_ -> {
                sources.requireIntent(work,intent);
                selections.verified(work,entry,name,mime,covered,status);
            });
        }
    }

    private static void requireSupported(GoogleDriveProvider.FileMetadata file) {
        if (file.trashed() || file.shortcutTargetId()!=null || "application/vnd.google-apps.shortcut".equals(file.mimeType()))
            throw SourceException.unsupportedRoot();
    }
}
