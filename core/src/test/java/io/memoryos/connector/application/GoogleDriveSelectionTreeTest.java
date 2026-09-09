package io.memoryos.connector.application;

import static io.memoryos.connector.application.GoogleDriveSelectionOperationTest.Fixture.file;
import static io.memoryos.connector.application.GoogleDriveSelectionOperationTest.Fixture.link;
import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.GoogleDriveSourceService.*;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.identity.ActorId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class GoogleDriveSelectionTreeTest {
    private HikariDataSource dataSource;

    private GoogleDriveSelectionOperationTest.Fixture fixture() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        return new GoogleDriveSelectionOperationTest.Fixture(dataSource);
    }

    @AfterEach
    void closeDatabase() { if (dataSource != null) dataSource.close(); }

    @Test
    void actualNestedFoldersKeepFilesWithoutLinksAndFileBranchesDeduplicateTargetsWithoutDroppingLocations() throws Exception {
        var f = fixture();
        var source = create(f, 2);
        var nested = file("nested", true, List.of("root0"));
        var plain = file("no-links", false, List.of("nested"));
        var document = file("document", false, List.of("nested"));
        f.files.put(nested.id(), nested);
        f.files.put(plain.id(), plain);
        f.files.put(document.id(), document);
        f.pages.put("root0|", new GoogleDriveProvider.FilePage(List.of(nested), null));
        f.pages.put("nested|", new GoogleDriveProvider.FilePage(List.of(plain, document), null));
        var locations = List.of(new LinkOrigin("root0", "document", "Document", "Sheet!B2"),
                new LinkOrigin("root0", "document", "Document", "Sheet!B3"),
                new LinkOrigin("root1", "document", "Document", "Sheet!B2"));
        save(f, source, List.of(candidate("remote", locations), candidate("root1", List.of(locations.getFirst())),
                candidate("second-hop", List.of(new LinkOrigin("root1", "root1", "Root file", "Paragraph 2")))), List.of());

        var roots = tree(f, source, null, null, 25);
        assertEquals(List.of("root0", "root1"), ids(roots));
        assertTrue(roots.items().stream().allMatch(SelectionTreeItem::expandable));
        assertEquals(List.of("nested"), ids(tree(f, source, "root0", null, 25)));
        var children = tree(f, source, "nested", null, 25);
        assertEquals(List.of("no-links", "document"), ids(children));
        assertTrue(children.items().stream().allMatch(SelectionTreeItem::coveredByRoots));
        assertFalse(children.items().getFirst().expandable());
        assertTrue(children.items().getLast().expandable());
        var links = tree(f, source, "document", null, 25);
        assertEquals(List.of("remote", "root1"), ids(links));
        assertEquals(locations, links.items().getFirst().origins());
        assertFalse(links.items().getFirst().selected());
        assertFalse(links.items().getFirst().expandable());
        assertTrue(links.items().getLast().selected());
        assertTrue(links.items().getLast().coveredByRoots());
        assertTrue(links.items().getLast().expandable());
        var firstLink = tree(f, source, "document", null, 1);
        assertEquals(List.of("remote"), ids(firstLink));
        assertEquals(locations, firstLink.items().getFirst().origins());
        assertNotNull(firstLink.nextCursor());
        var lastLink = tree(f, source, "document", firstLink.nextCursor(), 1);
        assertEquals(List.of("root1"), ids(lastLink));
        assertNull(lastLink.nextCursor());
        assertEquals(List.of("second-hop"), ids(tree(f, source, "root1", null, 25)));
        assertTrue(tree(f, source, "no-links", null, 25).items().isEmpty());
        assertEquals(List.of(), f.service.selectionDraft(f.owner, source).linkedDocumentIds());
    }

    @Test
    void disconnectedApprovedCyclesAndOrphansRemainAtTopLevelWithoutProviderReads() throws Exception {
        var f = fixture();
        var source = create(f, 2);
        save(f, source, List.of(
                candidate("reachable", List.of(new LinkOrigin("root0", "inside", "Inside", "A1"))),
                candidate("reachable-child", List.of(new LinkOrigin("reachable", "reachable", "Reachable", "A1"))),
                candidate("cycle-a", List.of(new LinkOrigin("cycle-b", "cycle-b", "Cycle B", "A1"))),
                candidate("cycle-b", List.of(new LinkOrigin("cycle-a", "cycle-a", "Cycle A", "A1"))),
                candidate("orphan", List.of())), List.of("reachable", "cycle-a", "cycle-b", "orphan"));
        int before = f.calls;
        var all = new ArrayList<String>();
        String cursor = null;
        do {
            var page = tree(f, source, null, cursor, 2);
            all.addAll(ids(page));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(List.of("root0", "root1", "cycle-a", "cycle-b", "orphan"), all);
        assertEquals(before, f.calls);
        assertTrue(f.listedParents.isEmpty());
        assertEquals(1, f.service.configuration(f.owner, source).revision());
        assertEquals(2, f.service.configuration(f.owner, source).discoveryRevision());
    }

    @Test
    void fileOnlyScopeDeniesArbitraryTargetsBeforeProviderReadsAndFolderScopeNeverListsUnprovenParents() throws Exception {
        var f = fixture();
        f.files.put("selected", file("selected", false, List.of()));
        var receipt = f.create(UUID.randomUUID(), List.of(link("selected")));
        f.finish(receipt);
        int before = f.calls;
        assertThrows(SourceException.class, () -> tree(f, receipt.sourceId(), "unapproved", null, 25));
        assertEquals(before, f.calls);
        var source = create(f, 1);
        f.files.put("outside", file("outside", true, List.of("ancestor0")));
        f.files.put("outside-file", file("outside-file", false, List.of("outside")));
        assertThrows(SourceException.class, () -> tree(f, source, "outside", null, 25));
        assertThrows(SourceException.class, () -> tree(f, source, "outside-file", null, 25));
        assertTrue(f.listedParents.isEmpty());
        assertThrows(SourceException.class, () -> f.service.selectionTree(new ActorId(UUID.randomUUID()), source, "root0", null, 25));
        assertTrue(f.listedParents.isEmpty());
    }

    @Test
    void approvalAuthorizesOnlySupportedFilesAndDoesNotAuthorizeFolderLinks() throws Exception {
        var f = fixture();
        var source = create(f, 2);
        f.files.put("approved", file("approved", false, List.of()));
        f.files.put("folder-link", file("folder-link", true, List.of()));
        save(f, source, List.of(candidate("approved", List.of()),
                new LinkedDocument("folder-link", "folder-link", "application/vnd.google-apps.folder", false, false,
                        LinkedDocumentStatus.UNSUPPORTED, List.of()),
                candidate("child", List.of(new LinkOrigin("approved", "approved", "Approved", "A1"))),
                candidate("hidden", List.of(new LinkOrigin("folder-link", "folder-link", "Folder", "A1")))),
                List.of("approved", "folder-link"));
        assertEquals(List.of("child"), ids(tree(f, source, "approved", null, 25)));
        assertThrows(SourceException.class, () -> tree(f, source, "folder-link", null, 25));
        assertFalse(tree(f, source, null, null, 25).items().stream().filter(item -> item.id().equals("folder-link")).findFirst().orElseThrow().expandable());
        assertTrue(f.listedParents.isEmpty());
    }

    @Test
    void providerPagesRetainEveryUnseenChildAcrossSmallerApiPagesAndRejectChangedPageOrder() throws Exception {
        var f = fixture();
        var source = create(f, 1);
        var files = new ArrayList<GoogleDriveProvider.FileMetadata>();
        for (int i = 0; i < 105; i++) files.add(file("child" + i, false, List.of("root0")));
        f.pages.put("root0|", new GoogleDriveProvider.FilePage(files.subList(0, 100), "next-provider-page"));
        f.pages.put("root0|next-provider-page", new GoogleDriveProvider.FilePage(files.subList(100, 105), null));
        var all = new ArrayList<String>();
        String cursor = null;
        for (int i = 0; i < 5; i++) {
            var page = tree(f, source, "root0", cursor, 25);
            all.addAll(ids(page));
            cursor = page.nextCursor();
        }
        assertNull(cursor);
        assertEquals(files.stream().map(GoogleDriveProvider.FileMetadata::id).toList(), all);
        var first = tree(f, source, "root0", null, 25);
        var changed = new ArrayList<>(files.subList(0, 100));
        java.util.Collections.swap(changed, 0, 1);
        f.pages.put("root0|", new GoogleDriveProvider.FilePage(changed, "next-provider-page"));
        assertThrows(SourceException.class, () -> tree(f, source, "root0", first.nextCursor(), 25));
    }

    @Test
    void cursorsRejectDifferentParentsSourcesMalformedInputAndStaleDiscovery() throws Exception {
        var f = fixture();
        var source = create(f, 2);
        var other = create(f, 2);
        var rootPage = tree(f, source, null, null, 1);
        assertNotNull(rootPage.nextCursor());
        assertThrows(SourceException.class, () -> tree(f, other, null, rootPage.nextCursor(), 1));
        assertThrows(SourceException.class, () -> tree(f, source, "root0", rootPage.nextCursor(), 1));
        assertThrows(SourceException.class, () -> tree(f, source, null, "%%%", 1));
        assertThrows(SourceException.class, () -> tree(f, source, "root", null, 1));
        save(f, source, List.of(candidate("new", List.of())), List.of());
        assertThrows(SourceException.class, () -> tree(f, source, null, rootPage.nextCursor(), 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"revision", "discovery_revision", "credential", "owner"})
    void authorityChangesDuringMetadataIoDiscardTheWholePage(String authority) throws Exception {
        var f = fixture();
        var source = create(f, 1);
        f.pages.put("root0|", new GoogleDriveProvider.FilePage(List.of(file("child", false, List.of("root0"))), null));
        f.afterProviderRead = () -> {
            f.afterProviderRead = () -> {};
            if (authority.equals("credential")) {
                f.jdbc.sql("UPDATE google_drive_credentials SET credential_revision=credential_revision+1 WHERE tenant_id=:tenant AND credential_id=:credential")
                        .param("tenant", f.tenant.value()).param("credential", f.credential.value()).update();
            } else if (authority.equals("owner")) {
                f.jdbc.sql("UPDATE tenant_memberships SET status='INACTIVE' WHERE tenant_id=:tenant AND actor_id=:owner")
                        .param("tenant", f.tenant.value()).param("owner", f.owner.value()).update();
            } else {
                f.jdbc.sql("UPDATE google_drive_sources SET " + authority + "=" + authority + "+1 WHERE tenant_id=:tenant AND source_id=:source")
                        .param("tenant", f.tenant.value()).param("source", source.value()).update();
            }
        };
        assertThrows(SourceException.class, () -> tree(f, source, "root0", null, 25));
    }

    @Test
    void generalScopeResolvesItsSavedMyDriveBoundaryAndRejectsForeignDriveChildren() throws Exception {
        var f = fixture();
        var root = file("my-drive", true, List.of());
        f.files.put("root", root);
        f.files.put(root.id(), root);
        var receipt = f.service.create(f.owner, UUID.randomUUID(), "My Drive", f.credential, ScopeMode.GENERAL, List.of());
        f.finish(receipt);
        var source = receipt.sourceId();
        assertEquals(List.of("my-drive"), ids(tree(f, source, null, null, 25)));
        f.pages.put("my-drive|", new GoogleDriveProvider.FilePage(List.of(file("inside", false, List.of("my-drive"))), null));
        assertEquals(List.of("inside"), ids(tree(f, source, "my-drive", null, 25)));
        f.pages.put("my-drive|", new GoogleDriveProvider.FilePage(List.of(new GoogleDriveProvider.FileMetadata(
                "foreign", "Foreign", "text/plain", "1", null, null, false, List.of("my-drive"), "shared-drive", null)), null));
        assertEquals(GoogleDriveProviderException.Failure.INCONSISTENT,
                assertThrows(GoogleDriveProviderException.class, () -> tree(f, source, "my-drive", null, 25)).failure());
        f.files.put("root", file("different-drive", true, List.of()));
        assertEquals(GoogleDriveProviderException.Failure.INCONSISTENT,
                assertThrows(GoogleDriveProviderException.class, () -> tree(f, source, "my-drive", null, 25)).failure());
    }

    @Test
    void ancestryCyclesDenyScopeAndOverlongPathsFailClosedWithoutListing() throws Exception {
        var f = fixture();
        var source = create(f, 1);
        f.files.put("cycle-a", file("cycle-a", true, List.of("cycle-b")));
        f.files.put("cycle-b", file("cycle-b", true, List.of("cycle-a")));
        assertThrows(SourceException.class, () -> tree(f, source, "cycle-a", null, 25));
        for (int i = 0; i < 66; i++) f.files.put("deep" + i, file("deep" + i, true, List.of(i == 65 ? "root0" : "deep" + (i + 1))));
        assertEquals(GoogleDriveProviderException.Failure.LIMIT_EXCEEDED,
                assertThrows(GoogleDriveProviderException.class, () -> tree(f, source, "deep0", null, 25)).failure());
        assertTrue(f.listedParents.isEmpty());
    }

    private static SourceId create(GoogleDriveSelectionOperationTest.Fixture f, int count) {
        var receipt = f.create(UUID.randomUUID(), f.mixedRoots(count, 1));
        f.finish(receipt);
        return receipt.sourceId();
    }

    private static SelectionTreePage tree(GoogleDriveSelectionOperationTest.Fixture f, SourceId source, String parent, String cursor, int size) {
        return f.service.selectionTree(f.owner, source, parent, cursor, size);
    }

    private static List<String> ids(SelectionTreePage page) { return page.items().stream().map(SelectionTreeItem::id).toList(); }
    private static LinkedDocument candidate(String id, List<LinkOrigin> origins) {
        return new LinkedDocument(id, id, "text/plain", false, false, LinkedDocumentStatus.AVAILABLE, origins);
    }
    private static void save(GoogleDriveSelectionOperationTest.Fixture f, SourceId source, List<LinkedDocument> documents, List<String> approvals) {
        f.transactions.executeWithoutResult(_ -> {
            f.roots.saveDiscovery(f.tenant, source, 1, 1, 0, documents, List.of());
            if (!approvals.isEmpty()) f.roots.replaceApprovals(f.tenant, source, approvals, List.of(), false);
        });
    }
}
