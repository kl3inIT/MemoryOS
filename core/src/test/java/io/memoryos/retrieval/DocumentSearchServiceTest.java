package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentSearchServiceTest {
    private final TenantAccessResolver tenants = mock(TenantAccessResolver.class);
    private final SourceDocumentAccessResolver access = mock(SourceDocumentAccessResolver.class);
    private final DocumentChunkPort documents = mock(DocumentChunkPort.class);
    private final OpenSearchIndexService index = mock(OpenSearchIndexService.class);
    private final DocumentSearchService service = new DocumentSearchService(tenants, access, documents, index, new SimpleMeterRegistry());
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final UUID generation = UUID.randomUUID();

    @Test
    void filtersObsoleteAndIneligibleHitsBeforeGroupingAndBoundedPaging() {
        var tenant = new TenantId(UUID.randomUUID());
        var first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var hidden = UUID.randomUUID();
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        when(index.identity()).thenReturn("space"); when(index.candidateLimit()).thenReturn(500);
        when(index.search(any(), any(), any(), any())).thenReturn(List.of(
                hit(hidden, generation, 0, 1), hit(first, UUID.randomUUID(), 0, .99),
                hit(second, generation, 0, .9), hit(first, generation, 2, .9), hit(first, generation, 1, .9)));
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(first, generation, second, generation, hidden, generation));
        when(access.readableDocuments(any(), any())).thenReturn(Set.of(first, second));
        var page = service.search(actor, new SearchRequest("nghỉ phép", List.of(), null, 0, 1));
        assertEquals(1, page.results().size()); assertTrue(page.hasMore());
        assertEquals(first, page.results().getFirst().documentId());
        var section = page.results().getFirst().sections().getFirst();
        assertEquals(1, page.results().getFirst().sections().size());
        assertEquals(1, section.startOrdinal());
        assertEquals(2, section.endOrdinal());
        assertEquals(1, section.matchingOrdinal());
        assertEquals("Passage 1\nPassage 2", section.content());
        var next = service.search(actor, new SearchRequest("nghỉ phép", List.of(), null, 1, 1));
        assertEquals(second, next.results().getFirst().documentId()); assertFalse(next.hasMore());
        assertEquals(0, next.results().getFirst().sections().getFirst().startOrdinal());
        assertEquals(0, next.results().getFirst().sections().getFirst().endOrdinal());
    }

    @Test
    void mergesAdjacentHitsInDocumentOrderWithBestMatchRankAndEveryChunksProvenance() {
        var document = UUID.randomUUID();
        givenHits(document, List.of(hit(document, generation, 11, .95), hit(document, generation, 40, .9),
                hit(document, generation, 10, .5), hit(document, generation, 11, .4)));

        var result = service.search(actor, new SearchRequest("nghỉ phép", List.of(), null, 0, 10)).results().getFirst();

        assertEquals(.95, result.score());
        assertEquals(2, result.sections().size());
        assertEquals(new SearchPage.Section(10, 11, 11, .95, "Passage 10\nPassage 11", List.of(
                new SearchPage.ChunkProvenance(10, "[{\"page\":10}]"),
                new SearchPage.ChunkProvenance(11, "[{\"page\":11}]"))), result.sections().getFirst());
        assertEquals(40, result.sections().getLast().startOrdinal());
        assertEquals(40, result.sections().getLast().endOrdinal());
        verify(documents, never()).read(any(), any(), any());
    }

    @Test
    void limitsSectionsAfterMergingAllAdjacentCandidatesAndRanksByBestHit() {
        var document = UUID.randomUUID();
        givenHits(document, List.of(hit(document, generation, 13, .95), hit(document, generation, 40, .9),
                hit(document, generation, 30, .8), hit(document, generation, 20, .7),
                hit(document, generation, 12, .3), hit(document, generation, 11, .2), hit(document, generation, 10, .1)));

        var sections = service.search(actor, new SearchRequest("leave", List.of(), null, 0, 10))
                .results().getFirst().sections();

        assertEquals(List.of(10, 40, 30), sections.stream().map(SearchPage.Section::startOrdinal).toList());
        assertEquals(13, sections.getFirst().endOrdinal());
        assertEquals(13, sections.getFirst().matchingOrdinal());
        assertEquals("Passage 10\nPassage 11\nPassage 12\nPassage 13", sections.getFirst().content());
        assertEquals(4, sections.getFirst().provenance().size());
    }

    @Test
    void keepsGapsSeparateAndResolvesEqualSectionScoresByMatchingOrdinal() {
        var document = UUID.randomUUID();
        givenHits(document, List.of(hit(document, generation, 12, .9), hit(document, generation, 10, .9)));

        var sections = service.search(actor, new SearchRequest("leave", List.of(), null, 0, 10))
                .results().getFirst().sections();

        assertEquals(List.of(10, 12), sections.stream().map(SearchPage.Section::startOrdinal).toList());
        assertEquals(List.of(10, 12), sections.stream().map(SearchPage.Section::endOrdinal).toList());
        verify(documents, never()).read(any(), any(), any());
    }

    private void givenHits(UUID document, List<SearchHit> hits) {
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(new TenantId(UUID.randomUUID())));
        when(index.identity()).thenReturn("space");
        when(index.candidateLimit()).thenReturn(500);
        when(index.search(any(), any(), any(), any())).thenReturn(hits);
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(document, generation));
        when(access.readableDocuments(any(), any())).thenReturn(Set.of(document));
    }

    @Test
    void unprovisionedActorCannotReachProviderAndUnavailableDocumentCannotBeRead() {
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.empty());
        assertThrows(SearchDocumentUnavailableException.class, () -> service.search(actor, new SearchRequest("hello", List.of(), null, 0, 10)));
        verifyNoInteractions(index);
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(new TenantId(UUID.randomUUID())));
        assertThrows(SearchDocumentUnavailableException.class, () -> service.document(actor, UUID.randomUUID(), generation, 0));
        verifyNoInteractions(documents);
    }

    private SearchHit hit(UUID document, UUID version, int ordinal, double score) {
        return new SearchHit(document, version, ordinal, "Title", "text/plain", "Passage " + ordinal,
                "[{\"page\":" + ordinal + "}]", Instant.EPOCH, score);
    }

    @Test
    void rankedQueriesFuseRanksAfterAuthorizationAndExpansionReusesThatAuthority() {
        var tenant = new TenantId(UUID.randomUUID());
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var hidden = UUID.randomUUID();
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        when(index.identity()).thenReturn("space");
        when(index.search(tenant, "leave", List.of(), null)).thenReturn(List.of(
                hit(hidden, generation, 0, 1), hit(first, generation, 3, .9), hit(first, generation, 3, .8), hit(second, generation, 1, .5)));
        when(index.search(tenant, "HR", List.of(), null)).thenReturn(List.of(hit(second, generation, 1, 9)));
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(first, generation, second, generation, hidden, generation));
        when(access.readableDocuments(any(), any())).thenReturn(Set.of(first, second));
        var result = service.ranked(actor, List.of(new SearchQuery("leave", false, .7), new SearchQuery("HR", true, 1)), () -> {});
        assertEquals(List.of(second, first), result.hits().stream().map(SearchHit::documentId).toList());
        assertEquals(.7 / 52 + 1.0 / 51, result.hits().getFirst().score(), .000001);
        org.mockito.Mockito.clearInvocations(access);
        var hit = result.hits().getFirst();
        when(documents.isCurrent(tenant, new DocumentId(second), generation, "space")).thenReturn(true);
        when(index.document(tenant, second, generation, 0, 4)).thenReturn(new SearchDocument(second, generation, "Title",
                List.of(new SearchPage.Passage(1, "Passage 1", "[]")), 0, 4, false));
        assertEquals(second, service.expand(result, hit, 2).documentId());
        verifyNoInteractions(access);
        assertThrows(SearchRequestException.class, () -> service.expand(result, hit(hidden, generation, 0, 1), 2));
        assertThrows(SearchRequestException.class, () -> service.expand(result, hit, 6));
        verify(documents, never()).read(any(), any(), any());
    }

    @Test
    void independentPreviewChecksPermissionAndGenerationThenReadsOnlyTheIndexWindow() {
        var tenant = new TenantId(UUID.randomUUID());
        var id = UUID.randomUUID();
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        when(index.identity()).thenReturn("space");
        when(access.canRead(actor, new DocumentId(id))).thenReturn(true);
        when(documents.isCurrent(tenant, new DocumentId(id), generation, "space")).thenReturn(true);
        var window = new SearchDocument(id, generation, "Title", List.of(), 20, 20, false);
        when(index.document(tenant, id, generation, 20, 20)).thenReturn(window);
        assertEquals(window, service.document(actor, id, generation, 20));
        when(access.canRead(actor, new DocumentId(id))).thenReturn(false);
        assertThrows(SearchDocumentUnavailableException.class, () -> service.document(actor, id, generation, 20));
        verify(index).document(tenant, id, generation, 20, 20);
        verify(documents, never()).read(any(), any(), any());
    }

    @Test
    void springMvcDebugFormattingCannotExpandQueriesOrDocumentText() {
        String privateText = "private compensation figures";
        assertFalse(new SearchRequest(privateText, List.of(), null, 0, 10).toString().contains(privateText));
        var result = new SearchPage.Result(UUID.randomUUID(), generation, privateText, "text/plain", Instant.EPOCH, .9,
                List.of(new SearchPage.Section(0, 0, 0, .9, privateText, List.of(new SearchPage.ChunkProvenance(0, "[]")))));
        assertFalse(new SearchPage(List.of(result), 0, false, 500).toString().contains(privateText));
        assertFalse(new SearchDocument(result.documentId(), generation, privateText,
                List.of(new SearchPage.Passage(0, privateText, "[]")), 0, 1, false)
                .toString().contains(privateText));
    }
}
