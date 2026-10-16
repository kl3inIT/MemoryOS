package io.memoryos.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchHit;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A search hit names a document; the service answers with every file still readable that holds it, in the search's
 * order, and drops a hit whose file stopped being readable while the search ran.
 */
class UserFileSearchServiceTest {
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantAccessResolver tenants = mock(TenantAccessResolver.class);
    private final JdbcUserFileRepository files = mock(JdbcUserFileRepository.class);
    private final DocumentSearchService search = mock(DocumentSearchService.class);
    private final UserFileSearchService service = new UserFileSearchService(files, mock(FileAttachments.class), tenants,
            search);

    private static SearchHit hit(UUID document) {
        return new SearchHit(document, UUID.randomUUID(), 0, "title", "application/pdf", "text", "{}", Instant.now(), 1.0);
    }

    private static Map<UUID, UUID> ordered(UUID... fileThenDocument) {
        var map = new LinkedHashMap<UUID, UUID>();
        for (int index = 0; index < fileThenDocument.length; index += 2) map.put(fileThenDocument[index], fileThenDocument[index + 1]);
        return map;
    }

    @Test
    void mapsEachHitToTheReadableFilesOfItsDocumentInSearchOrder() {
        var shared = UUID.randomUUID();
        var single = UUID.randomUUID();
        var first = UUID.randomUUID();
        var copy = UUID.randomUUID();
        var revoked = UUID.randomUUID();
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        // The scope the search runs over, then what is still readable once it returns: the revoked file is gone.
        when(files.documents(eq(tenant), eq(actor), anySet(), any()))
                .thenReturn(ordered(first, shared, copy, shared, revoked, single))
                .thenReturn(ordered(first, shared, copy, shared));
        var sharedHit = hit(shared);
        var singleHit = hit(single);
        var secondSharedHit = hit(shared);
        when(search.searchFiles(eq(actor), eq(tenant), any(), eq("contract")))
                .thenReturn(List.of(sharedHit, singleHit, secondSharedHit));

        var found = service.search(actor, tenant, Set.of(first, copy, revoked), "contract");

        assertEquals(List.of(new UserFileSearchService.FileHit(first, sharedHit),
                new UserFileSearchService.FileHit(copy, sharedHit),
                new UserFileSearchService.FileHit(first, secondSharedHit),
                new UserFileSearchService.FileHit(copy, secondSharedHit)), found);
    }
}
