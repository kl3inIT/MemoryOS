package io.memoryos.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.library.persistence.JdbcLibraryRepository;
import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectWriteService;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.retrieval.SearchHit;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Content search reads the owner's own indexed uploads through the file search Chat already uses, then presents
 * them as library rows. The collaborators are stubbed here: what matters is the scope handed to the search, the
 * grouping of its passages and that a file the library no longer lists is dropped.
 */
class LibraryContentSearchTest {
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantAccessResolver tenants = mock(TenantAccessResolver.class);
    private final JdbcLibraryRepository library = mock(JdbcLibraryRepository.class);
    private final JdbcUserFileRepository files = mock(JdbcUserFileRepository.class);
    private final UserFileSearchService fileSearch = mock(UserFileSearchService.class);
    private final LibraryService service = new LibraryService(tenants, library, files,
            mock(FileAttachments.class), mock(LibraryArtifacts.class), mock(ObjectStorage.class),
            mock(ObjectWriteService.class), new UserFileProperties(104857600L, 104857600L),
            mock(StorageQuotaService.class), mock(PlatformTransactionManager.class), fileSearch);

    private static LibraryFile row(UUID id, String filename) {
        return new LibraryFile(LibraryFile.Source.UPLOAD, id, filename, "application/pdf", 10,
                Instant.now(), LibraryFile.Category.DOCUMENT, null, null, null, false,
                UserFile.Status.READY, null, null, null, List.of());
    }

    private static SearchHit hit(UUID document, int ordinal, String content) {
        return new SearchHit(document, UUID.randomUUID(), ordinal, "title", "application/pdf", content, "{}",
                Instant.now(), 1.0);
    }

    @Test
    void groupsAtMostThreePassagesPerFileAndKeepsTheSearchOrder() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        when(files.searchable(tenant, actor, 4000)).thenReturn(List.of(first, second));
        when(fileSearch.search(eq(actor), eq(tenant), anySet(), eq("điều khoản"))).thenReturn(List.of(
                new UserFileSearchService.FileHit(second, hit(UUID.randomUUID(), 1, "điều khoản thanh toán")),
                new UserFileSearchService.FileHit(first, hit(UUID.randomUUID(), 4, "điều khoản bảo hành")),
                new UserFileSearchService.FileHit(second, hit(UUID.randomUUID(), 2, "b")),
                new UserFileSearchService.FileHit(second, hit(UUID.randomUUID(), 3, "c")),
                new UserFileSearchService.FileHit(second, hit(UUID.randomUUID(), 9, "a fourth passage"))));
        when(library.page(eq(tenant), eq(actor), any(), any(), eq(0), eq(2)))
                .thenReturn(new JdbcLibraryRepository.Page(List.of(row(first, "bao-hanh.pdf"),
                        row(second, "hop-dong.pdf")), 2, 20));

        var matches = service.searchContent(actor, "  điều khoản  ");

        // Best match first, as the search ranked it, and a file carries at most three passages.
        assertEquals(List.of(second, first), matches.stream().map(match -> match.file().id()).toList());
        assertEquals(List.of("điều khoản thanh toán", "b", "c"),
                matches.getFirst().passages().stream().map(LibraryService.Passage::text).toList());
        assertEquals(1, matches.getFirst().passages().getFirst().ordinal());
        assertEquals("hop-dong.pdf", matches.getFirst().file().filename());

        // The scope is the owner's own indexed uploads, nothing else.
        verify(fileSearch).search(actor, tenant, Set.of(first, second), "điều khoản");
    }

    @Test
    void dropsAFileTheLibraryNoLongerListsAndAsksNothingWithoutIndexedUploads() {
        var gone = UUID.randomUUID();
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        when(files.searchable(tenant, actor, 4000)).thenReturn(List.of(gone));
        when(fileSearch.search(eq(actor), eq(tenant), anySet(), eq("hợp đồng"))).thenReturn(List.of(
                new UserFileSearchService.FileHit(gone, hit(UUID.randomUUID(), 1, "hợp đồng"))));
        when(library.page(eq(tenant), eq(actor), any(), any(), eq(0), eq(1)))
                .thenReturn(new JdbcLibraryRepository.Page(List.of(), 0, 0));
        assertTrue(service.searchContent(actor, "hợp đồng").isEmpty());

        when(files.searchable(tenant, actor, 4000)).thenReturn(List.of());
        assertTrue(service.searchContent(actor, "hợp đồng").isEmpty());
        verify(fileSearch, never()).search(any(), any(), eq(Set.of()), any());
    }

    @Test
    void refusesAnEmptyOrOverlongQuery() {
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        for (var bad : List.of("   ", "x".repeat(501)))
            assertThrows(LibraryException.class, () -> service.searchContent(actor, bad));
    }
}
