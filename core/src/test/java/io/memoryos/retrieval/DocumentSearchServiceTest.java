package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.group.Authority;
import io.memoryos.iam.group.IamAccess;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
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
    private final IamAuthorization authorization = mock(IamAuthorization.class);
    private final SourceDocumentAccessResolver access = mock(SourceDocumentAccessResolver.class);
    private final DocumentChunkPort documents = mock(DocumentChunkPort.class);
    private final OpenSearchIndexService index = mock(OpenSearchIndexService.class);
    private final SourceSearchService sourceSearch = mock(SourceSearchService.class);
    private final DocumentSearchService service = new DocumentSearchService(tenants, authorization, access, documents, index, new SimpleMeterRegistry(), sourceSearch,
            new SearchTimings(new SimpleMeterRegistry(), io.micrometer.observation.ObservationRegistry.NOOP));
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final UUID generation = UUID.randomUUID();

    @Test
    void privateFileReaderRejectsWrongScopeAndStaleGenerationWithoutSourceAuthorization() {
        var tenant = new TenantId(UUID.randomUUID());
        var document = UUID.randomUUID();
        var file = UUID.randomUUID();
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        when(index.identity()).thenReturn("space");
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(document, generation));
        var result = new SearchDocument(document, generation, "Private", List.of(), 5, 8, false);
        when(index.document(tenant, document, generation, 5, 20)).thenReturn(result);
        assertEquals(result, service.fileDocument(actor, tenant, Map.of(file, document), document, generation, 5));
        assertThrows(SearchDocumentUnavailableException.class, () -> service.fileDocument(actor, tenant, Map.of(file, document), document, UUID.randomUUID(), 5));
        assertThrows(SearchRequestException.class, () -> service.fileDocument(actor, tenant, Map.of(file, document), document, generation, -1));
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(document, generation)).thenReturn(Map.of());
        assertThrows(SearchDocumentUnavailableException.class, () -> service.fileDocument(actor, tenant, Map.of(file, document), document, generation, 5));
        verifyNoInteractions(access);
    }

    @Test
    void filtersObsoleteAndIneligibleHitsBeforeGroupingAndBoundedPaging() {
        var tenant = new TenantId(UUID.randomUUID());
        var first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var hidden = UUID.randomUUID();
        givenSearchAccess(tenant);
        when(index.identity()).thenReturn("space"); when(index.candidateLimit()).thenReturn(500);
        when(index.search(any(), any(ActorId.class), any(), any(), any(), any())).thenReturn(List.of(
                hit(hidden, generation, 0, 1), hit(first, UUID.randomUUID(), 0, .99),
                hit(second, generation, 0, .9), hit(first, generation, 2, .9), hit(first, generation, 1, .9)));
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(first, generation, second, generation, hidden, generation));
        when(access.readableDocuments(any(), any())).thenReturn(Set.of(first, second));
        var page = service.search(actor, new SearchRequest("nghỉ phép", List.of(), null, 0, 1, List.of()));
        assertEquals(1, page.results().size()); assertTrue(page.hasMore()); assertEquals(2, page.totalResults());
        assertEquals(first, page.results().getFirst().documentId());
        var section = page.results().getFirst().sections().getFirst();
        assertEquals(1, page.results().getFirst().sections().size());
        assertEquals(1, section.startOrdinal());
        assertEquals(2, section.endOrdinal());
        assertEquals(1, section.matchingOrdinal());
        assertEquals("Passage 1\nPassage 2", section.content());
        var next = service.search(actor, new SearchRequest("nghỉ phép", List.of(), null, 1, 1, List.of()));
        assertEquals(second, next.results().getFirst().documentId()); assertFalse(next.hasMore()); assertEquals(2, next.totalResults());
        assertEquals(0, next.results().getFirst().sections().getFirst().startOrdinal());
        assertEquals(0, next.results().getFirst().sections().getFirst().endOrdinal());
    }

    @Test
    void mergesAdjacentHitsInDocumentOrderWithBestMatchRankAndEveryChunksProvenance() {
        var document = UUID.randomUUID();
        givenHits(document, List.of(hit(document, generation, 11, .95), hit(document, generation, 40, .9),
                hit(document, generation, 10, .5), hit(document, generation, 11, .4)));

        var result = service.search(actor, new SearchRequest("nghỉ phép", List.of(), null, 0, 10, List.of())).results().getFirst();

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

        var sections = service.search(actor, new SearchRequest("leave", List.of(), null, 0, 10, List.of()))
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

        var sections = service.search(actor, new SearchRequest("leave", List.of(), null, 0, 10, List.of()))
                .results().getFirst().sections();

        assertEquals(List.of(10, 12), sections.stream().map(SearchPage.Section::startOrdinal).toList());
        assertEquals(List.of(10, 12), sections.stream().map(SearchPage.Section::endOrdinal).toList());
        verify(documents, never()).read(any(), any(), any());
    }

    @Test
    void countsConnectorsAcrossEveryCandidateAndFiltersWithinThoseCandidates() {
        var tenant = new TenantId(UUID.randomUUID());
        UUID upload = UUID.randomUUID(), drive = UUID.randomUUID(), both = UUID.randomUUID();
        givenSearchAccess(tenant);
        when(index.identity()).thenReturn("space"); when(index.candidateLimit()).thenReturn(500);
        when(index.search(any(), any(ActorId.class), any(), any(), any(), any())).thenReturn(List.of(
                hit(upload, generation, 0, .9), hit(drive, generation, 0, .8), hit(both, generation, 0, .7)));
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(upload, generation, drive, generation, both, generation));
        when(access.readableDocuments(any(), any())).thenReturn(Set.of(upload, drive, both));
        var scope = new SourceSearchScope(tenant, actor, Map.of());
        when(sourceSearch.scope(actor)).thenReturn(scope);
        when(sourceSearch.readableMetadata(scope, List.of(upload, drive, both))).thenReturn(Map.of(
                upload, List.of(origin(SourceType.FILE)), drive, List.of(origin(SourceType.GOOGLE_DRIVE)),
                both, List.of(origin(SourceType.FILE), origin(SourceType.GOOGLE_DRIVE))));
        var facets = new SearchPage.SourceFacets(3, List.of(
                new SearchPage.SourceTypeFacet(SourceType.FILE, 2), new SearchPage.SourceTypeFacet(SourceType.GOOGLE_DRIVE, 2)));

        var everything = service.search(actor, new SearchRequest("báo cáo", List.of(), null, 0, 1, List.of()));
        assertEquals(3, everything.totalResults());
        assertEquals(facets, everything.sourceFacets());
        assertEquals(List.of(SourceType.FILE), everything.results().getFirst().sourceTypes());

        var driveOnly = service.search(actor, new SearchRequest("báo cáo", List.of(), null, 0, 10, List.of(SourceType.GOOGLE_DRIVE)));
        assertEquals(List.of(drive, both), driveOnly.results().stream().map(SearchPage.Result::documentId).toList());
        assertEquals(2, driveOnly.totalResults());
        assertFalse(driveOnly.hasMore());
        assertEquals(facets, driveOnly.sourceFacets());
        assertEquals(List.of(SourceType.FILE, SourceType.GOOGLE_DRIVE), driveOnly.results().getLast().sourceTypes());
        verify(sourceSearch, times(2)).readableMetadata(scope, List.of(upload, drive, both));
    }

    private DocumentSourceMetadata origin(SourceType type) {
        return new DocumentSourceMetadata(UUID.randomUUID(), UUID.randomUUID(), type, Instant.EPOCH, Instant.EPOCH, List.of());
    }

    private void givenHits(UUID document, List<SearchHit> hits) {
        givenSearchAccess(new TenantId(UUID.randomUUID()));
        when(index.identity()).thenReturn("space");
        when(index.candidateLimit()).thenReturn(500);
        when(index.search(any(), any(ActorId.class), any(), any(), any(), any())).thenReturn(hits);
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(document, generation));
        when(access.readableDocuments(any(), any())).thenReturn(Set.of(document));
    }

    private void givenSearchAccess(TenantId tenant) {
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        when(authorization.require(actor, IamCapability.SEARCH_READ, false))
                .thenReturn(new IamAccess(tenant, Authority.GLOBAL));
    }

    @Test
    void activeMemberWithoutSearchGrantCannotReachProvider() {
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(new TenantId(UUID.randomUUID())));
        when(authorization.require(actor, IamCapability.SEARCH_READ, false))
                .thenThrow(new IamException(IamFailureReason.ACCESS_DENIED, "Search grant required"));

        var failure = assertThrows(IamException.class,
                () -> service.search(actor, new SearchRequest("hello", List.of(), null, 0, 10, List.of())));

        assertEquals(IamFailureReason.ACCESS_DENIED.code(), failure.code());
        verifyNoInteractions(index, documents, access);
    }

    @Test
    void activeMemberWithoutSearchGrantCannotReadPassages() {
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(new TenantId(UUID.randomUUID())));
        when(authorization.require(actor, IamCapability.SEARCH_READ, false))
                .thenThrow(new IamException(IamFailureReason.ACCESS_DENIED, "Search grant required"));

        var failure = assertThrows(IamException.class,
                () -> service.document(actor, UUID.randomUUID(), generation, 0));

        assertEquals(IamFailureReason.ACCESS_DENIED.code(), failure.code());
        verifyNoInteractions(index, documents, access);
    }

    @Test
    void unprovisionedActorCannotReachProviderOrReadPassages() {
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.empty());
        assertThrows(SearchDocumentUnavailableException.class, () -> service.search(actor, new SearchRequest("hello", List.of(), null, 0, 10, List.of())));
        assertThrows(SearchDocumentUnavailableException.class, () -> service.document(actor, UUID.randomUUID(), generation, 0));
        verifyNoInteractions(index, documents, access, authorization);
    }

    @Test
    void searchGrantDoesNotAllowReadingAnIneligibleDocument() {
        givenSearchAccess(new TenantId(UUID.randomUUID()));
        assertThrows(SearchDocumentUnavailableException.class, () -> service.document(actor, UUID.randomUUID(), generation, 0));
        verifyNoInteractions(index, documents);
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
        var source = UUID.randomUUID();
        var scope = new SourceSearchScope(tenant, actor, Map.of(source, SourceType.FILE));
        var origin = new DocumentSourceMetadata(source, UUID.randomUUID(), SourceType.FILE, Instant.EPOCH, Instant.EPOCH, List.of());
        when(index.batch(any(), any(), any(), any())).thenReturn(List.of(List.of(
                hit(hidden, generation, 0, 1), hit(first, generation, 3, .9), hit(first, generation, 3, .8), hit(second, generation, 1, .5)),
                List.of(hit(second, generation, 1, 9))));
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(first, generation, second, generation, hidden, generation));
        when(sourceSearch.readableMetadata(any(), any())).thenReturn(Map.of(first, List.of(origin), second, List.of(origin)));
        var result = service.ranked(scope, List.of(new SearchQuery("leave", false, .7), new SearchQuery("HR", true, 1)), SearchFilters.NONE, () -> {});
        assertEquals(List.of(second, first), result.hits().stream().map(SearchHit::documentId).toList());
        assertEquals(.7 / 52 + 1.0 / 51, result.hits().getFirst().score(), .000001);
        org.mockito.Mockito.clearInvocations(access);
        when(documents.isCurrent(tenant, new DocumentId(second), generation, "space")).thenReturn(true);
        when(index.document(tenant, second, generation, 0, 1)).thenReturn(new SearchDocument(second, generation, "Title",
                List.of(new SearchPage.Passage(0, "Passage 0", "[]")), 0, 4, true));
        when(index.document(tenant, second, generation, 2, 2)).thenReturn(new SearchDocument(second, generation, "Title",
                List.of(new SearchPage.Passage(2, "Passage 2", "[]")), 2, 3, false));
        assertEquals(List.of(0, 1, 2), service.window(result, result.sections().getFirst(), 2).stream().map(SearchPage.Passage::ordinal).toList());
        verifyNoInteractions(access);
        var foreign = hit(hidden, generation, 0, 1);
        assertThrows(SearchRequestException.class, () -> service.window(result, new SearchSection(foreign, List.of(foreign)), 2));
        assertThrows(SearchRequestException.class, () -> service.window(result, result.sections().getFirst(), 6));
        assertThrows(SearchRequestException.class, () -> service.authorizedSections(result, List.of(new SearchSection(foreign, List.of(foreign)))));
        verify(documents, never()).read(any(), any(), any());
    }

    @Test
    void independentPreviewChecksPermissionAndGenerationThenReadsOnlyTheIndexWindow() {
        var tenant = new TenantId(UUID.randomUUID());
        var id = UUID.randomUUID();
        givenSearchAccess(tenant);
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
    void chatCitationReaderNeedsOnlyMembershipAndDocumentEligibility() {
        var tenant = new TenantId(UUID.randomUUID());
        var id = UUID.randomUUID();
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        when(authorization.require(eq(actor), any(IamCapability.class), eq(false)))
                .thenThrow(new IamException(IamFailureReason.ACCESS_DENIED, "No capability"));
        when(index.identity()).thenReturn("space");
        when(access.canRead(actor, new DocumentId(id))).thenReturn(true);
        when(documents.isCurrent(tenant, new DocumentId(id), generation, "space")).thenReturn(true);
        var window = new SearchDocument(id, generation, "Title", List.of(), 0, 1, false);
        when(index.document(tenant, id, generation, 0, 20)).thenReturn(window);
        assertEquals(window, service.citation(actor, id, generation, 0));
        assertThrows(IamException.class, () -> service.document(actor, id, generation, 0));
        when(access.canRead(actor, new DocumentId(id))).thenReturn(false);
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citation(actor, id, generation, 0));
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.empty());
        assertThrows(SearchDocumentUnavailableException.class, () -> service.citation(actor, id, generation, 0));
    }

    @Test
    void authorizesTheUnionInBoundedBatchesAndMergesPastThirtyChunksBeforeSelection() {
        var scope = new SourceSearchScope(new TenantId(UUID.randomUUID()), actor, Map.of(UUID.randomUUID(), SourceType.FILE));
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(scope.tenant()));
        var origin = new DocumentSourceMetadata(scope.sources().keySet().iterator().next(), UUID.randomUUID(),
                SourceType.FILE, Instant.EPOCH, Instant.EPOCH, List.of());
        var document = UUID.randomUUID();
        var hits = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> hit(document, generation, i, 1)).toList());
        java.util.stream.IntStream.range(0, 1001).forEach(_ -> hits.add(hit(UUID.randomUUID(), generation, 0, .5)));
        when(index.identity()).thenReturn("space");
        when(index.batch(any(), any(), any(), any())).thenReturn(List.of(hits, hits));
        var batches = new java.util.ArrayList<List<UUID>>();
        when(documents.currentGenerations(any(), any(), any())).thenAnswer(call -> {
            List<UUID> ids = call.getArgument(1); batches.add(List.copyOf(ids));
            assertTrue(ids.size() <= 1000);
            return ids.stream().collect(java.util.stream.Collectors.toMap(id -> id, _ -> generation));
        });
        when(sourceSearch.readableMetadata(any(), any())).thenAnswer(call -> call.<List<UUID>>getArgument(1)
                .stream().collect(java.util.stream.Collectors.toMap(id -> id, _ -> List.of(origin))));
        var result = service.ranked(scope, List.of(new SearchQuery("leave", false, 1), new SearchQuery("HR", true, 1)), SearchFilters.NONE, () -> {});
        assertEquals(List.of(1000, 2), batches.stream().map(List::size).toList());
        verify(sourceSearch, times(2)).readableMetadata(any(), any());
        assertEquals(1041, result.hits().size());
        assertEquals(40, result.sections().getFirst().chunks().size());
        assertEquals(39, result.sections().getFirst().end());
        assertEquals(List.of(result.sections().getFirst()), service.authorizedSections(result, List.of(result.sections().getFirst())));
        verify(index, never()).document(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void equalFusionScoresUseFirstSourceRankThenFirstQueryInsteadOfDocumentId() {
        UUID first = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), second = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var source = UUID.randomUUID();
        var scope = new SourceSearchScope(new TenantId(UUID.randomUUID()), actor, Map.of(source, SourceType.FILE));
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(scope.tenant()));
        var origin = new DocumentSourceMetadata(source, UUID.randomUUID(), SourceType.FILE, Instant.EPOCH, Instant.EPOCH, List.of());
        when(index.identity()).thenReturn("space");
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(first, generation, second, generation));
        when(sourceSearch.readableMetadata(any(), any())).thenReturn(Map.of(first, List.of(origin), second, List.of(origin)));
        var a = hit(first, generation, 0, 1); var b = hit(second, generation, 0, 1);
        when(index.batch(any(), any(), any(), any())).thenReturn(List.of(List.of(a, b), List.of(b, a)))
                .thenReturn(List.of(List.of(a), List.of(b)));
        var queries = List.of(new SearchQuery("A", false, 1), new SearchQuery("B", true, 1));
        for (int i = 0; i < 2; i++) {
            var hits = service.ranked(scope, queries, SearchFilters.NONE, () -> {}).hits();
            assertEquals(hits.get(0).score(), hits.get(1).score());
            assertEquals(List.of(first, second), hits.stream().map(SearchHit::documentId).toList());
        }
    }

    @Test
    void directSearchRejectsGrantRevocationDuringProviderIo() {
        var tenant = new TenantId(UUID.randomUUID());
        givenSearchAccess(tenant);
        when(index.search(any(), any(ActorId.class), any(), any(), any(), any())).thenAnswer(_ -> {
            when(authorization.require(actor, IamCapability.SEARCH_READ, false))
                    .thenThrow(new IamException(IamFailureReason.ACCESS_DENIED, "Search grant revoked"));
            return List.of();
        });
        assertThrows(IamException.class, () -> service.search(actor, new SearchRequest("private", List.of(), null, 0, 10, List.of())));
    }

    @Test
    void directSearchDropsPrivateHitsWhenMembershipIsRevokedDuringProviderIo() {
        var document = UUID.randomUUID();
        givenHits(document, List.of(hit(document, generation, 0, 1)));
        when(index.search(any(), any(ActorId.class), any(), any(), any(), any())).thenAnswer(_ -> {
            when(access.readableDocuments(any(), any())).thenReturn(Set.of());
            return List.of(hit(document, generation, 0, 1));
        });
        assertTrue(service.search(actor, new SearchRequest("private", List.of(), null, 0, 10, List.of())).results().isEmpty());
    }

    @Test
    void previewRejectsGroupRevocationDuringIndexWindowRead() {
        var tenant = new TenantId(UUID.randomUUID());
        var id = UUID.randomUUID();
        givenSearchAccess(tenant);
        when(index.identity()).thenReturn("space");
        when(access.canRead(actor, new DocumentId(id))).thenReturn(true);
        when(documents.isCurrent(tenant, new DocumentId(id), generation, "space")).thenReturn(true);
        when(index.document(tenant, id, generation, 0, 20)).thenAnswer(_ -> {
            when(access.canRead(actor, new DocumentId(id))).thenReturn(false);
            return new SearchDocument(id, generation, "Private", List.of(new SearchPage.Passage(0, "secret", "[]")), 0, 1, false);
        });
        assertThrows(SearchDocumentUnavailableException.class, () -> service.document(actor, id, generation, 0));
    }

    @Test
    void previewRejectsSearchGrantRevocationDuringIndexWindowRead() {
        var tenant = new TenantId(UUID.randomUUID());
        var id = UUID.randomUUID();
        givenSearchAccess(tenant);
        when(index.identity()).thenReturn("space");
        when(access.canRead(actor, new DocumentId(id))).thenReturn(true);
        when(documents.isCurrent(tenant, new DocumentId(id), generation, "space")).thenReturn(true);
        when(index.document(tenant, id, generation, 0, 20)).thenAnswer(_ -> {
            when(authorization.require(actor, IamCapability.SEARCH_READ, false))
                    .thenThrow(new IamException(IamFailureReason.ACCESS_DENIED, "Search grant revoked"));
            return new SearchDocument(id, generation, "Private", List.of(), 0, 1, false);
        });
        assertThrows(IamException.class, () -> service.document(actor, id, generation, 0));
    }

    @Test
    void rankedSearchDropsRevokedOriginsAfterProviderIoWithoutRequiringAChatCapability() {
        var scope = new SourceSearchScope(new TenantId(UUID.randomUUID()), actor, Map.of(UUID.randomUUID(), SourceType.FILE));
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(scope.tenant()));
        var id = UUID.randomUUID();
        when(index.identity()).thenReturn("space");
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(id, generation));
        when(index.batch(any(), any(), any(), any())).thenAnswer(_ -> {
            when(sourceSearch.readableMetadata(scope, List.of(id))).thenReturn(Map.of());
            return List.of(List.of(hit(id, generation, 0, 1)));
        });
        assertTrue(service.ranked(scope, List.of(new SearchQuery("private", false, 1)), SearchFilters.NONE, () -> {}).hits().isEmpty());
        verifyNoInteractions(authorization);
    }

    @Test
    void rankedSearchRejectsActorDeactivationDuringProviderIoEvenWithNoHits() {
        var scope = new SourceSearchScope(new TenantId(UUID.randomUUID()), actor, Map.of(UUID.randomUUID(), SourceType.FILE));
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(scope.tenant()));
        when(index.batch(any(), any(), any(), any())).thenAnswer(_ -> {
            when(tenants.findActiveTenant(actor)).thenReturn(Optional.empty());
            return List.of(List.of());
        });
        assertThrows(SearchDocumentUnavailableException.class,
                () -> service.ranked(scope, List.of(new SearchQuery("private", false, 1)), SearchFilters.NONE, () -> {}));
    }

    @Test
    void sectionRecheckDropsRevokedGroupAccessWithoutIndexIo() {
        var fixture = expansionFixture();
        assertEquals(List.of(fixture.section()), service.authorizedSections(fixture.results(), List.of(fixture.section())));
        when(sourceSearch.readableMetadata(fixture.scope(), List.of(fixture.hit().documentId()))).thenReturn(Map.of());
        assertTrue(service.authorizedSections(fixture.results(), List.of(fixture.section())).isEmpty());
        verify(index, never()).document(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void windowReadsNeighborsWithoutDatabaseRechecksAndTheFinalRecheckDropsRevocationDuringTheRead() {
        var fixture = expansionFixture();
        org.mockito.Mockito.clearInvocations(documents, tenants);
        when(index.document(fixture.scope().tenant(), fixture.hit().documentId(), generation, 1, 1)).thenAnswer(_ -> {
            when(sourceSearch.readableMetadata(fixture.scope(), List.of(fixture.hit().documentId()))).thenReturn(Map.of());
            return new SearchDocument(fixture.hit().documentId(), generation, "Private",
                    List.of(new SearchPage.Passage(1, "secret", "[]")), 1, 4, true);
        });
        when(index.document(fixture.scope().tenant(), fixture.hit().documentId(), generation, 3, 1)).thenReturn(new SearchDocument(
                fixture.hit().documentId(), generation, "Private", List.of(new SearchPage.Passage(3, "after", "[]")), 3, 4, false));
        assertEquals(List.of(1, 2, 3), service.window(fixture.results(), fixture.section(), 1).stream().map(SearchPage.Passage::ordinal).toList());
        verify(documents, never()).currentGenerations(any(), any(), any());
        verify(tenants, never()).findActiveTenant(any());
        assertTrue(service.authorizedSections(fixture.results(), List.of(fixture.section())).isEmpty());
        verifyNoInteractions(authorization);
    }

    @Test
    void sectionRecheckBatchesDocumentsIntoOneGenerationAndOneMetadataQuery() {
        var fixture = expansionFixture();
        var other = hit(UUID.randomUUID(), generation, 0, 1).withOrigins(fixture.hit().origins());
        var results = new SearchResults(fixture.scope(), List.of(fixture.hit(), other));
        when(documents.currentGenerations(any(), any(), any())).thenReturn(Map.of(fixture.hit().documentId(), generation, other.documentId(), generation));
        when(sourceSearch.readableMetadata(any(), any())).thenReturn(Map.of(fixture.hit().documentId(), fixture.hit().origins()));
        org.mockito.Mockito.clearInvocations(documents, sourceSearch);
        var kept = service.authorizedSections(results, results.sections());
        assertEquals(List.of(fixture.hit().documentId()), kept.stream().map(section -> section.anchor().documentId()).toList());
        verify(documents, times(1)).currentGenerations(any(), any(), any());
        verify(sourceSearch, times(1)).readableMetadata(any(), any());
    }

    @Test
    void remainingPublicOriginCannotAuthorizeStalePrivateSourceMetadataDuringRecheck() {
        var fixture = expansionFixture();
        var publicOrigin = new DocumentSourceMetadata(UUID.randomUUID(), UUID.randomUUID(), SourceType.FILE, Instant.EPOCH, Instant.EPOCH, List.of());
        var scope = new SourceSearchScope(fixture.scope().tenant(), actor, Map.of(
                publicOrigin.sourceId(), SourceType.FILE, fixture.hit().origins().getFirst().sourceId(), SourceType.FILE));
        var mixed = fixture.hit().withOrigins(List.of(publicOrigin, fixture.hit().origins().getFirst()));
        var results = new SearchResults(scope, List.of(mixed));
        when(sourceSearch.readableMetadata(scope, List.of(mixed.documentId()))).thenReturn(Map.of(mixed.documentId(), List.of(publicOrigin)));
        assertTrue(service.authorizedSections(results, results.sections()).isEmpty());
    }

    @Test
    void sectionRecheckKeepsNothingForDeactivatedActorDespiteItsOldAuthorizedResults() {
        var fixture = expansionFixture();
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.empty());
        assertTrue(service.authorizedSections(fixture.results(), List.of(fixture.section())).isEmpty());
        verify(index, never()).document(any(), any(), any(), anyInt(), anyInt());
    }

    private ExpansionFixture expansionFixture() {
        var source = UUID.randomUUID();
        var scope = new SourceSearchScope(new TenantId(UUID.randomUUID()), actor, Map.of(source, SourceType.FILE));
        var origin = new DocumentSourceMetadata(source, UUID.randomUUID(), SourceType.FILE, Instant.EPOCH, Instant.EPOCH, List.of());
        var hit = hit(UUID.randomUUID(), generation, 2, 1).withOrigins(List.of(origin));
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(scope.tenant()));
        when(index.identity()).thenReturn("space");
        when(documents.currentGenerations(scope.tenant(), List.of(hit.documentId()), "space")).thenReturn(Map.of(hit.documentId(), generation));
        when(sourceSearch.readableMetadata(scope, List.of(hit.documentId()))).thenReturn(Map.of(hit.documentId(), List.of(origin)));
        var results = new SearchResults(scope, List.of(hit));
        return new ExpansionFixture(scope, hit, results, results.sections().getFirst());
    }

    private record ExpansionFixture(SourceSearchScope scope, SearchHit hit, SearchResults results, SearchSection section) {}

    @Test
    void springMvcDebugFormattingCannotExpandQueriesOrDocumentText() {
        String privateText = "private compensation figures";
        assertFalse(new SearchRequest(privateText, List.of(), null, 0, 10, List.of()).toString().contains(privateText));
        var result = new SearchPage.Result(UUID.randomUUID(), generation, privateText, "text/plain", Instant.EPOCH, .9,
                List.of(new SearchPage.Section(0, 0, 0, .9, privateText, List.of(new SearchPage.ChunkProvenance(0, "[]")))));
        assertFalse(new SearchPage(List.of(result), 0, false, 1, 500, new SearchPage.SourceFacets(1, List.of())).toString().contains(privateText));
        assertFalse(new SearchDocument(result.documentId(), generation, privateText,
                List.of(new SearchPage.Passage(0, privateText, "[]")), 0, 1, false)
                .toString().contains(privateText));
    }
}
