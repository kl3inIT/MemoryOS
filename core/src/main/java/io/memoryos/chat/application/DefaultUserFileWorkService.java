package io.memoryos.chat.application;

import io.memoryos.chat.UserFileWork;
import io.memoryos.chat.UserFileWorkPort;
import io.memoryos.chat.persistence.JdbcUserFileWorkRepository;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.DocumentContent;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.chat.ChatException;
import io.memoryos.objectstorage.ObjectUploadService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultUserFileWorkService implements UserFileWorkPort {
    private final JdbcUserFileWorkRepository work;
    private final DocumentCommandPort documents;
    private final ObjectUploadService uploads;
    private final TenantAccessResolver tenants;

    public DefaultUserFileWorkService(JdbcUserFileWorkRepository work, DocumentCommandPort documents, ObjectUploadService uploads,
            TenantAccessResolver tenants) {
        this.work = work; this.documents = documents; this.uploads = uploads; this.tenants = tenants;
    }

    @Override @Transactional
    public Optional<UserFileWork> claim(TenantId tenant, UUID operation, UUID delivery) { return work.claim(tenant, operation, delivery); }
    @Override @Transactional
    public boolean renew(UserFileWork claim) { return work.renew(claim); }

    @Override @Transactional
    public boolean complete(UserFileWork claim, DocumentContent content) {
        tenants.lockActiveMembership(claim.owner()).filter(membership -> membership.tenantId().equals(claim.tenantId()))
                .orElseThrow(ChatException::unavailable);
        if (!work.lockCurrent(claim)) return false;
        var metadata = new java.util.HashMap<>(content.metadata());
        metadata.put("origin", "USER_FILE");
        metadata.put("user_file_id", claim.fileId().toString());
        metadata.put("owner_actor_id", claim.owner().value().toString());
        var privateContent = new DocumentContent(content.mediaType(), content.title(), content.normalizedText(),
                metadata, content.structuredJson(), content.extractionArtifactId());
        var id = documents.publish(claim.tenantId(), null, privateContent, claim.object().metadata().checksum().value());
        work.completed(claim, id, content.normalizedText(), content.mediaType());
        return true;
    }

    @Override @Transactional
    public boolean deleted(UserFileWork claim) {
        if (!work.lockCurrent(claim)) return false;
        var refs = work.detach(claim);
        if (refs.document() != null) documents.removeUnreferenced(claim.tenantId(), List.of(refs.document()));
        // Existing upload cleanup owns durable raw-object deletion and retries.
        uploads.retireAdopted(claim.tenantId(), refs.upload());
        return true;
    }

    @Override @Transactional
    public void failed(UserFileWork claim, String code) { work.failed(claim, code); }
}
