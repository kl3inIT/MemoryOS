package io.memoryos.library;

import io.memoryos.library.persistence.JdbcLibraryArchiveRepository;
import io.memoryos.library.persistence.JdbcLibraryRepository;
import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.library.work.DefaultUserFileWorkService;
import io.memoryos.library.work.persistence.JdbcUserFileWorkRepository;
import org.springframework.context.annotation.Import;

/**
 * The library beans the Worker runs, for a Worker that does not scan the library: file extraction work (MEM-9), the
 * upload and trash upkeep and the release of a purged temporary conversation's uploads, library archives (MEM-152),
 * and the library contents Chat's export packs (MEM-153). The Worker imports this class; it is deliberately not a
 * component, so the API, which scans all of {@code io.memoryos}, registers these beans only once.
 */
@Import({
        JdbcUserFileWorkRepository.class,
        DefaultUserFileWorkService.class,
        JdbcUserFileRepository.class,
        UserFileMaintenance.class,
        JdbcLibraryRepository.class,
        LibraryContents.class,
        JdbcLibraryArchiveRepository.class,
        LibraryArchiveService.class
})
public class LibraryWorkerComponents {
}
