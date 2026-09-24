package io.memoryos.library;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How much one person's file library may hold in this deployment. It is a deployment ceiling like the upload
 * limit next to it, not something an administrator records per Tenant: the bytes live in one object store whose
 * size is an operational fact, so the number belongs to the environment
 * ({@code MEMORYOS_CHAT_STORAGE_LIBRARY_BYTES}) rather than to a settings page.
 *
 * <p>{@code 0} means no limit, which is how Chat behaved before a limit existed. The default, 512 MiB, is a
 * real bound so the storage meter a person sees always means something.
 */
@ConfigurationProperties("memoryos.chat.storage")
public record ChatStorageProperties(@DefaultValue("536870912") long libraryBytes) {
    /** A byte less than nothing is not a limit, and no deployment holds a pebibyte for one person. */
    public static final long CEILING = 1024L * 1024 * 1024 * 1024;

    public ChatStorageProperties {
        if (libraryBytes < 0 || libraryBytes > CEILING) {
            throw new IllegalArgumentException(
                    "memoryos.chat.storage.library-bytes must be between 0 (no limit) and 1 TiB");
        }
    }

    /** The limit, or empty when this deployment sets none. */
    public java.util.Optional<Long> libraryLimit() {
        return libraryBytes == 0 ? java.util.Optional.empty() : java.util.Optional.of(libraryBytes);
    }
}
