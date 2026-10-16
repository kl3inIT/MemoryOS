package io.memoryos.chat.files;

import io.memoryos.chat.interpreter.persistence.JdbcInterpreterRepository;
import io.memoryos.chat.files.persistence.JdbcChatArtifactRepository;
import io.memoryos.chat.image.persistence.JdbcImageArtifactRepository;
import io.memoryos.library.LibraryFile;
import io.memoryos.library.LibraryArtifacts;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The library's changes to Chat's generated files and images: a rename or a star, and their trash. The library has
 * already taken the owner lock in its own transaction; these run inside it.
 */
@Component
public class ChatLibraryArtifacts implements LibraryArtifacts {
    private final JdbcChatArtifactRepository artifacts;
    private final JdbcInterpreterRepository generated;
    private final JdbcImageArtifactRepository images;

    public ChatLibraryArtifacts(JdbcChatArtifactRepository artifacts, JdbcInterpreterRepository generated,
                         JdbcImageArtifactRepository images) {
        this.artifacts = artifacts; this.generated = generated; this.images = images;
    }

    @Override
    public boolean update(TenantId tenant, ActorId owner, LibraryFile.Source source, UUID id,
                          @Nullable String filename, @Nullable Boolean favorite) {
        return artifacts.update(tenant, owner, source, id, filename, favorite);
    }

    @Override
    public boolean restore(TenantId tenant, ActorId owner, LibraryFile.Source source, UUID id) {
        return switch (source) {
            case GENERATED -> generated.restoreArtifact(tenant, owner, id);
            case IMAGE -> images.restore(tenant, owner, id);
            case UPLOAD, MEETING -> throw new IllegalArgumentException("an upload is the library's own file");
        };
    }

    @Override
    public boolean purgeNow(TenantId tenant, ActorId owner, LibraryFile.Source source, UUID id) {
        return switch (source) {
            case GENERATED -> generated.purgeArtifactNow(tenant, owner, id);
            case IMAGE -> images.purgeNow(tenant, owner, id);
            case UPLOAD, MEETING -> throw new IllegalArgumentException("an upload is the library's own file");
        };
    }

    @Override
    public int purgeTrashed(TenantId tenant, ActorId owner, int limit) {
        return generated.purgeTrashedArtifacts(tenant, owner, limit) + images.purgeTrashed(tenant, owner, limit);
    }
}
