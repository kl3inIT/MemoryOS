package io.memoryos.library;

import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadPurpose;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.UploadAuthorization;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@EnableConfigurationProperties({UserFileProperties.class, LibraryTrashProperties.class})
public class UserFileService {
    private final TenantAccessResolver tenants;
    private final JdbcUserFileRepository files;
    private final FileAttachments attachments;
    private final ObjectUploadService uploads;
    private final UserFileProperties policy;
    private final StorageQuotaService quotas;
    private final LibraryTrashProperties trash;
    private final TransactionTemplate tx;

    public UserFileService(TenantAccessResolver tenants, JdbcUserFileRepository files, FileAttachments attachments,
            ObjectUploadService uploads, UserFileProperties policy, StorageQuotaService quotas,
            LibraryTrashProperties trash, PlatformTransactionManager transactionManager) {
        this.tenants = tenants; this.files = files; this.attachments = attachments; this.uploads = uploads;
        this.policy = policy; this.quotas = quotas; this.trash = trash;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record UploadInput(UUID requestId, String filename, String mediaType, long sizeBytes, String sha256) {}
    public record UploadReceipt(UserFile file, @Nullable UploadAuthorization upload) {}

    public UserFileProperties policy(ActorId actor) { tenant(actor); return policy; }

    public UploadReceipt initiate(ActorId actor, UploadInput input) {
        Objects.requireNonNull(input.requestId(), "requestId");
        policy.validateSize(input.sizeBytes());
        var spec = new ObjectUploadSpecification(input.filename(), input.mediaType(), input.sizeBytes(),
                new ContentSha256(input.sha256()), ObjectUploadPurpose.CHAT_FILE);
        return Objects.requireNonNull(tx.execute(ignored -> {
            var tenant = write(actor);
            var prior = files.request(tenant, actor, input.requestId());
            if (prior.isPresent()) {
                var row = prior.get();
                if (!row.file().filename().equals(spec.filename()) || !row.declaredMediaType().equals(spec.mediaType())
                        || row.file().sizeBytes() != spec.sizeBytes() || !row.checksum().equals(spec.checksum())) {
                    throw LibraryException.conflict();
                }
                return new UploadReceipt(row.file(), row.file().status() == UserFile.Status.UPLOADING
                        ? uploads.resume(tenant, uploadOf(row), ObjectUploadPurpose.CHAT_FILE).authorization() : null);
            }
            // Refused before the upload is authorized, so a file over the limit is never written at all.
            quotas.requireRoom(tenant, actor, spec.sizeBytes());
            var upload = uploads.initiate(tenant, spec);
            var id = files.create(tenant, actor, input.requestId(), upload.uploadId(), spec);
            return new UploadReceipt(owned(tenant, actor, id, false).file(), upload.authorization());
        }));
    }

    public UserFile finalizeUpload(ActorId actor, UUID id) {
        var initialTenant = tenant(actor);
        var initial = owned(initialTenant, actor, id, false);
        if (initial.file().status() != UserFile.Status.UPLOADING) return initial.file();
        policy.validateSize(initial.file().sizeBytes());
        var verified = uploads.verify(initialTenant, uploadOf(initial), ObjectUploadPurpose.CHAT_FILE);
        // Storage IO is outside locks. Membership and owner are checked again at commit.
        return Objects.requireNonNull(tx.execute(ignored -> {
            var tenant = write(actor);
            if (!tenant.equals(initialTenant)) throw LibraryException.unavailable();
            var current = owned(tenant, actor, id, true);
            if (current.file().status() != UserFile.Status.UPLOADING) return current.file();
            policy.validateSize(current.file().sizeBytes());
            uploads.adopt(tenant, uploadOf(current), verified.token());
            files.finalized(tenant, id, verified.object().id());
            return owned(tenant, actor, id, false).file();
        }));
    }

    public UserFile get(ActorId actor, UUID id) { return owned(tenant(actor), actor, id, false).file(); }

    /**
     * The READY files a turn, a Project or an agent may carry, in the order asked for. Called inside the caller's
     * transaction, after the owner lock; each file row is share-locked until it commits.
     */
    public List<UserFile> admit(TenantId tenant, ActorId actor, List<UUID> ids) {
        if (ids.size() > 20 || ids.stream().anyMatch(Objects::isNull) || ids.stream().distinct().count() != ids.size()) throw LibraryException.invalid("Use at most 20 unique files.");
        // Agent users may admit files attached to an agent they can use.
        var viaAgents = attachments.readableThroughAgents(tenant, actor, ids);
        return ids.stream().map(id -> {
            var file = files.readable(tenant, actor, id, viaAgents, true).orElseThrow(LibraryException::unavailable).file();
            if (file.status() != UserFile.Status.READY) throw LibraryException.conflict();
            return file;
        }).toList();
    }

    /** A file the actor owns or an agent they can use grants them, in any status; empty when neither. */
    public Optional<UserFile> readable(TenantId tenant, ActorId actor, UUID id) {
        return files.readable(tenant, actor, id, attachments.readableThroughAgents(tenant, actor, List.of(id)), false)
                .map(JdbcUserFileRepository.Row::file);
    }

    /**
     * Gives the uploads a temporary conversation's question carried to that conversation: the library stops listing
     * them, and {@link UserFileMaintenance#releaseTemporary} releases them when the conversation is purged. Called
     * inside the turn transaction.
     */
    public void claimTemporary(TenantId tenant, ActorId actor, UUID session, List<UUID> ids) {
        files.claimTemporary(tenant, actor, session, ids);
    }

    public record FileText(String text, int offset, int totalCharacters) {}

    public FileText read(ActorId actor, UUID id, int offset, int count) {
        return read(actor, tenant(actor), id, offset, count);
    }

    public FileText read(ActorId actor, TenantId expectedTenant, UUID id, int offset, int count) {
        var tenant = tenant(actor);
        if (!tenant.equals(expectedTenant)) throw LibraryException.unavailable();
        if (offset < 0 || offset > 2000000 || count < 1 || count > 16000) throw LibraryException.invalid("Invalid file range.");
        var window = files.plaintext(tenant, actor, id, attachments.readableThroughAgents(tenant, actor, List.of(id)),
                offset, count).orElseThrow(LibraryException::unavailable);
        return new FileText(window.text(), window.offset(), window.totalCharacters());
    }

    public UserFile retry(ActorId actor, UUID id) {
        return Objects.requireNonNull(tx.execute(ignored -> {
            var tenant = write(actor);
            var file = owned(tenant, actor, id, true).file();
            if (file.status() != UserFile.Status.FAILED) throw LibraryException.conflict();
            if ("UPLOAD_EXPIRED".equals(file.errorCode())) throw LibraryException.invalid("The abandoned upload expired. Select the local file and upload again.");
            policy.validateSize(file.sizeBytes());
            files.retry(tenant, id);
            return owned(tenant, actor, id, false).file();
        }));
    }

    public UserFile delete(ActorId actor, UUID id) {
        return Objects.requireNonNull(tx.execute(ignored -> {
            var tenant = write(actor);
            var file = owned(tenant, actor, id, true).file();
            if (file.status() != UserFile.Status.DELETING && file.status() != UserFile.Status.DELETED) {
                var usage = attachments.holders(tenant, List.of(id));
                if (!usage.isEmpty()) throw new UserFileInUseException(usage.stream()
                        .map(used -> new UserFileInUseException.Usage(used.kind().name(), used.id(), used.name())).toList());
                files.delete(tenant, id, file.status() == UserFile.Status.UPLOADING || "UPLOAD_EXPIRED".equals(file.errorCode()),
                        trash.trashAfter());
            }
            return owned(tenant, actor, id, false).file();
        }));
    }

    public List<UserFile> recent(ActorId actor, int offset, int limit) {
        Paging.check(offset, limit);
        return files.recent(tenant(actor), actor, offset, limit);
    }

    private JdbcUserFileRepository.Row owned(TenantId tenant, ActorId actor, UUID id, boolean lock) {
        return files.owned(tenant, actor, id, lock).orElseThrow(LibraryException::unavailable);
    }

    /** Only a browser upload is ever UPLOADING; a server-written copy is handed to the worker as it is inserted. */
    private static ObjectUploadId uploadOf(JdbcUserFileRepository.Row row) {
        return Objects.requireNonNull(row.uploadId(), "an uploading file names its upload");
    }

    private TenantId tenant(ActorId actor) { return tenants.findActiveTenant(actor).orElseThrow(LibraryException::unavailable); }
    private TenantId write(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(LibraryException::unavailable).tenantId();
        files.lockOwner(tenant, actor); return tenant;
    }
}
