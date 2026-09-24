package io.memoryos.chat.application;

import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.chat.persistence.JdbcUserFileWorkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The Worker's recurring upkeep of uploaded files: abandoned uploads and uploads whose trash window has passed. */
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
}
