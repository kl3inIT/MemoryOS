package io.memoryos.chat;

import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectContent;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ChatFileContentService {
    private final JdbcUserFileRepository files;
    private final TenantAccessResolver tenants;
    private final ObjectStorage storage;
    public ChatFileContentService(JdbcUserFileRepository files, TenantAccessResolver tenants, ObjectStorage storage) {
        this.files = files; this.tenants = tenants; this.storage = storage;
    }
    public ObjectContent open(ActorId actor, TenantId tenant, UUID id) {
        if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()) throw ChatException.unavailable();
        var reference = files.raw(tenant, actor, id).orElseThrow(ChatException::unavailable);
        var content = storage.open(reference.key());
        try {
            if (!content.metadata().equals(reference.metadata())
                    || tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                    || files.raw(tenant, actor, id).filter(reference::equals).isEmpty())
                throw ChatException.unavailable();
            return content;
        } catch (RuntimeException failed) {
            content.close();
            throw failed;
        }
    }
    public ObjectContent open(ActorId actor, UUID id) {
        return open(actor, tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable), id);
    }
    public byte[] image(ActorId actor, TenantId tenant, UUID id) {
        var file = files.owned(tenant, actor, id, false).orElseThrow(ChatException::unavailable).file();
        if (!java.util.Set.of("image/png", "image/jpeg", "image/webp").contains(file.mediaType()) || file.sizeBytes() > 20971520)
            throw ChatException.invalid("Image is unavailable or exceeds the vision limit.");
        try (var input = open(actor, tenant, id)) {
            var bytes = input.inputStream().readNBytes(Math.toIntExact(file.sizeBytes()) + 1);
            if (bytes.length != file.sizeBytes()) throw ChatException.unavailable();
            if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()
                    || files.owned(tenant, actor, id, false).filter(row -> row.file().status() == UserFile.Status.READY).isEmpty())
                throw ChatException.unavailable();
            return bytes;
        } catch (java.io.IOException failed) { throw ChatException.unavailable(); }
    }
}
