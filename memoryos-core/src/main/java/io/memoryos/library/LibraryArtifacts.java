package io.memoryos.library;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Changes to the files and images Chat generated, which the library lists beside uploads but Chat owns. Chat
 * implements it; the library calls it inside its own transaction, after the owner lock, for a {@code GENERATED} or
 * {@code IMAGE} source only.
 */
public interface LibraryArtifacts {
    /** Renames or stars an artifact the owner's library lists; false when there is no such artifact. */
    boolean update(TenantId tenant, ActorId owner, LibraryFile.Source source, UUID id,
                   @Nullable String filename, @Nullable Boolean favorite);

    /** Takes an artifact out of the trash while its bytes are still there; false otherwise. */
    boolean restore(TenantId tenant, ActorId owner, LibraryFile.Source source, UUID id);

    /** Ends an artifact's trash window now; false when it is not in the trash. */
    boolean purgeNow(TenantId tenant, ActorId owner, LibraryFile.Source source, UUID id);

    /** Ends the trash window of up to {@code limit} artifacts of each kind; answers how many. */
    int purgeTrashed(TenantId tenant, ActorId owner, int limit);
}
