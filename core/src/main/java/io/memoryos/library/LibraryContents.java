package io.memoryos.library;

import io.memoryos.library.persistence.JdbcChatLibraryRepository;
import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * What a person's library holds, for packing it into a ZIP: the library's own archives and Chat's export of
 * everything a person has. Both run in the Worker, for an owner resolved from a recorded request, so neither checks
 * a signed-in membership; they only ever read the owner's own files.
 */
@Service
public class LibraryContents {
    private final JdbcChatLibraryRepository library;
    private final JdbcUserFileRepository files;
    private final ObjectStorage storage;

    public LibraryContents(JdbcChatLibraryRepository library, JdbcUserFileRepository files, ObjectStorage storage) {
        this.library = library; this.files = files; this.storage = storage;
    }

    /**
     * The files the owner's library lists right now, newest first and at most {@code max}: every one when
     * {@code only} is null, otherwise those of the selection that are still listed.
     */
    public List<ChatLibraryFile> listed(TenantId tenant, ActorId owner, @Nullable Collection<ChatLibraryArchiveItem> only,
                                        int max) {
        Set<String> sources = only == null ? Set.of()
                : only.stream().map(file -> file.source().name()).collect(Collectors.toUnmodifiableSet());
        // A null id set is what asks for every file; an empty one would ask for none.
        Set<UUID> ids = only == null ? null
                : only.stream().map(ChatLibraryArchiveItem::id).collect(Collectors.toUnmodifiableSet());
        var filter = new JdbcChatLibraryRepository.Filter("", sources, Set.of(), null, false, false, ids);
        return library.page(tenant, owner, filter, ChatLibraryFile.Sort.NEWEST, 0, max).items();
    }

    /**
     * A listed file's bytes, or empty when it is gone by now. An upload's bytes come from its adopted upload, an
     * artifact's from its own object.
     */
    public Optional<byte[]> read(TenantId tenant, ActorId owner, ChatLibraryFile file) {
        Optional<ObjectKey> key = file.source() == ChatLibraryFile.Source.UPLOAD
                ? files.raw(tenant, owner, file.id(), List.of()).map(row -> row.reference().key())
                : library.artifact(tenant, owner, file.source(), file.id()).map(JdbcChatLibraryRepository.Artifact::key);
        if (key.isEmpty()) return Optional.empty();
        try (var content = storage.open(key.get())) {
            return Optional.of(content.inputStream().readAllBytes());
        } catch (ObjectStorageException failure) {
            if (failure.code() == ObjectStorageFailureCode.NOT_FOUND) return Optional.empty();
            throw failure;
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
    }

    /** ZIP entries must not collide, so a repeated name gains " (2)", " (3)", as a download folder does. */
    public static String unique(Set<String> taken, String filename) {
        if (taken.add(filename)) return filename;
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        for (int index = 2; ; index++) {
            String candidate = stem + " (" + index + ")" + extension;
            if (taken.add(candidate)) return candidate;
        }
    }
}
