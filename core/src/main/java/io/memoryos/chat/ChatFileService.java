package io.memoryos.chat;

import io.memoryos.chat.application.ChatFileProperties;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectUploadPurpose;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.UploadAuthorization;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@EnableConfigurationProperties(ChatFileProperties.class)
public class ChatFileService {
    private final TenantAccessResolver tenants;
    private final JdbcChatRepository chats;
    private final JdbcUserFileRepository files;
    private final ObjectUploadService uploads;
    private final ChatFileProperties policy;
    private final TransactionTemplate tx;

    public ChatFileService(TenantAccessResolver tenants, JdbcChatRepository chats, JdbcUserFileRepository files,
            ObjectUploadService uploads, ChatFileProperties policy, PlatformTransactionManager transactionManager) {
        this.tenants = tenants; this.chats = chats; this.files = files; this.uploads = uploads;
        this.policy = policy; this.tx = new TransactionTemplate(transactionManager);
    }

    public record UploadInput(UUID requestId, String filename, String mediaType, long sizeBytes, String sha256) {}
    public record UploadReceipt(UserFile file, @Nullable UploadAuthorization upload) {}

    public ChatFileProperties policy(ActorId actor) { tenant(actor); return policy; }

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
                    throw ChatException.conflict();
                }
                return new UploadReceipt(row.file(), row.file().status() == UserFile.Status.UPLOADING
                        ? uploads.resume(tenant, row.uploadId(), ObjectUploadPurpose.CHAT_FILE).authorization() : null);
            }
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
        var verified = uploads.verify(initialTenant, initial.uploadId(), ObjectUploadPurpose.CHAT_FILE);
        // Storage IO is outside locks. Membership and owner are checked again at commit.
        return Objects.requireNonNull(tx.execute(ignored -> {
            var tenant = write(actor);
            if (!tenant.equals(initialTenant)) throw ChatException.unavailable();
            var current = owned(tenant, actor, id, true);
            if (current.file().status() != UserFile.Status.UPLOADING) return current.file();
            policy.validateSize(current.file().sizeBytes());
            uploads.adopt(tenant, current.uploadId(), verified.token());
            files.finalized(tenant, id);
            return owned(tenant, actor, id, false).file();
        }));
    }

    public UserFile get(ActorId actor, UUID id) { return owned(tenant(actor), actor, id, false).file(); }

    /** Called inside the turn/settings transaction, after the owner lock. */
    public List<ChatFileDescriptor> admit(TenantId tenant, ActorId actor, List<UUID> ids) {
        if (ids.size() > 20 || ids.stream().anyMatch(Objects::isNull) || ids.stream().distinct().count() != ids.size()) throw ChatException.invalid("Use at most 20 unique files.");
        return ids.stream().map(id -> {
            var file = owned(tenant, actor, id, true).file();
            if (file.status() != UserFile.Status.READY) throw ChatException.conflict();
            return ChatFileDescriptor.from(file);
        }).toList();
    }

    public record FileText(String text, int offset, int totalCharacters) {}

    public FileText read(ActorId actor, UUID id, int offset, int count) {
        return read(actor, tenant(actor), id, offset, count);
    }

    public FileText read(ActorId actor, TenantId expectedTenant, UUID id, int offset, int count) {
        var tenant = tenant(actor);
        if (!tenant.equals(expectedTenant)) throw ChatException.unavailable();
        if (offset < 0 || offset > 2000000 || count < 1 || count > 16000) throw ChatException.invalid("Invalid file range.");
        var window = files.plaintext(tenant, actor, id, offset, count).orElseThrow(ChatException::unavailable);
        return new FileText(window.text(), window.offset(), window.totalCharacters());
    }

    public UserFile retry(ActorId actor, UUID id) {
        return Objects.requireNonNull(tx.execute(ignored -> {
            var tenant = write(actor);
            var file = owned(tenant, actor, id, true).file();
            if (file.status() != UserFile.Status.FAILED) throw ChatException.conflict();
            if ("UPLOAD_EXPIRED".equals(file.errorCode())) throw ChatException.invalid("The abandoned upload expired. Select the local file and upload again.");
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
                if (files.usedByWorkspace(tenant, id)) throw ChatException.conflict();
                files.delete(tenant, id, file.status() == UserFile.Status.UPLOADING || "UPLOAD_EXPIRED".equals(file.errorCode()));
            }
            return owned(tenant, actor, id, false).file();
        }));
    }

    public List<UserFile> recent(ActorId actor, int offset, int limit) {
        ChatPersonaService.page(offset, limit);
        return files.recent(tenant(actor), actor, offset, limit);
    }

    private JdbcUserFileRepository.Row owned(TenantId tenant, ActorId actor, UUID id, boolean lock) {
        return files.owned(tenant, actor, id, lock).orElseThrow(ChatException::unavailable);
    }

    private TenantId tenant(ActorId actor) { return tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable); }
    private TenantId write(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor); return tenant;
    }
}
