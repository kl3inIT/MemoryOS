package io.memoryos.chat.application;

import io.memoryos.chat.UserFileWork;
import io.memoryos.chat.UserFileWorkPort;
import io.memoryos.chat.persistence.JdbcUserFileWorkRepository;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.DocumentContent;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.chat.ChatException;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectWriteService;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultUserFileWorkService implements UserFileWorkPort {
    private final JdbcUserFileWorkRepository work;
    private final DocumentCommandPort documents;
    private final ObjectUploadService uploads;
    private final TenantAccessResolver tenants;
    private final StoredObjectRegistry storedObjects;
    private final ObjectWriteService writes;
    private final ObjectStorage storage;

    public DefaultUserFileWorkService(JdbcUserFileWorkRepository work, DocumentCommandPort documents, ObjectUploadService uploads,
            TenantAccessResolver tenants, StoredObjectRegistry storedObjects, ObjectWriteService writes, ObjectStorage storage) {
        this.work = work; this.documents = documents; this.uploads = uploads; this.tenants = tenants;
        this.storedObjects = storedObjects; this.writes = writes; this.storage = storage;
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
        if (refs.upload() != null) {
            // Existing upload cleanup owns durable raw-object deletion and retries.
            uploads.retireAdopted(claim.tenantId(), refs.upload());
        } else {
            // A copy the server wrote has no upload; its object is an adopted write of this capability (V127).
            releaseWritten(claim.tenantId(), refs.object(), refs.objectKey());
        }
        releaseWritten(claim.tenantId(), refs.thumbnail(), refs.thumbnailKey());
        return true;
    }

    /**
     * Deletes an object this capability wrote and adopted: a copy's bytes or the library thumbnail derived from
     * a file. Adopted writes are never selected by the generic reapers, so the capability releases their bytes
     * and ownership here; the columns naming them were cleared in the same transaction, so a deleted file leaves
     * no unreferenced object. A storage failure rolls the release back and the DELETE work retries it.
     */
    private void releaseWritten(TenantId tenant, @Nullable StoredObjectId object, @Nullable ObjectKey key) {
        if (object == null || key == null) return;
        storedObjects.markDeletePending(tenant, object);
        storage.delete(key);
        writes.releaseAdopted(tenant, object);
        storedObjects.remove(tenant, object);
    }

    @Override @Transactional
    public void failed(UserFileWork claim, String code, @Nullable String errorMessage, @Nullable String errorDetail) { work.failed(claim, code, errorMessage, errorDetail); }
}
