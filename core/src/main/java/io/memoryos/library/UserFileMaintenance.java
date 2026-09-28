package io.memoryos.library;

import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.library.work.persistence.JdbcUserFileWorkRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The recurring upkeep of uploaded files: abandoned uploads, uploads whose trash window has passed, and the uploads of
 * a purged temporary conversation.
 */
@Service
public class UserFileMaintenance {
    private final JdbcUserFileWorkRepository work;
    private final JdbcUserFileRepository files;

    public UserFileMaintenance(JdbcUserFileWorkRepository work, JdbcUserFileRepository files) {
        this.work = work;
        this.files = files;
    }

    /** Marks uploads whose object upload expired before it finished; returns how many. */
    @Transactional
    public int expireUploads(int limit) {
        return work.expireUploads(limit);
    }

    /**
     * Queues the byte release of deleted uploads whose trash window has passed (MEM-152 phase 4); the existing
     * file DELETE work owns the release itself. Returns how many were queued.
     */
    @Transactional
    public int queueTrashPurges(int limit) {
        return files.enqueueDuePurges(limit);
    }

    /**
     * Releases the uploads a temporary conversation owned, when Chat purges it; an ordinary conversation owns none.
     * Runs in the purge's transaction. Returns how many were handed to the file work.
     */
    @Transactional
    public int releaseTemporary(UUID session) {
        return files.releaseTemporary(session);
    }
}
