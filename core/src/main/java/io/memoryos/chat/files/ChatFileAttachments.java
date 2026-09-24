package io.memoryos.chat.files;

import io.memoryos.chat.files.persistence.JdbcChatFileAttachmentRepository;
import io.memoryos.library.FileAttachments;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Chat's answer to the library: which agents grant an upload to their users, and what attaches it. */
@Component
public class ChatFileAttachments implements FileAttachments {
    private final JdbcChatFileAttachmentRepository attachments;

    public ChatFileAttachments(JdbcChatFileAttachmentRepository attachments) { this.attachments = attachments; }

    @Override
    public Set<UUID> readableThroughAgents(TenantId tenant, ActorId actor, Collection<UUID> files) {
        return attachments.usableThroughAgents(tenant, actor, files);
    }

    @Override
    public List<Holder> holders(TenantId tenant, Collection<UUID> files) {
        return attachments.holders(tenant, files);
    }
}
