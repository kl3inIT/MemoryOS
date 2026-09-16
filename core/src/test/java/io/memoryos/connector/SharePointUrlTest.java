package io.memoryos.connector;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SharePointUrlTest {

    @Test
    void readsSiteLibraryAndFolderAddresses() {
        var site = SharePointUrl.parse("https://memoryosvadan.sharepoint.com/sites/MemoryOSVi");
        assertEquals(SharePointUrl.Kind.SITE, site.kind());
        assertEquals("/sites/MemoryOSVi", site.sitePath());
        assertNull(site.librarySegment());

        var library = SharePointUrl.parse("https://memoryosvadan.sharepoint.com/sites/MemoryOSVi/Shared%20Documents");
        assertEquals(SharePointUrl.Kind.LIBRARY, library.kind());
        // The percent-encoded segment is kept decoded so it can be compared with what Graph reports.
        assertEquals("Shared Documents", library.librarySegment());
        assertEquals("/sites/MemoryOSVi/Shared Documents", library.path());

        var folder = SharePointUrl.parse("https://memoryosvadan.sharepoint.com/sites/MemoryOSVi/Shared%20Documents/Baocao/Quy%201");
        assertEquals(SharePointUrl.Kind.FOLDER, folder.kind());
        assertEquals(List.of("Baocao", "Quy 1"), folder.folderSegments());
        assertEquals("https://memoryosvadan.sharepoint.com/sites/MemoryOSVi/Shared Documents/Baocao/Quy 1", folder.canonical());
    }

    @Test
    void acceptsTeamsPersonalAndSharingLinks() {
        assertEquals("/teams/Finance", SharePointUrl.parse("https://contoso.sharepoint.com/teams/Finance").sitePath());

        var personal = SharePointUrl.parse(
                "https://memoryosvadan-my.sharepoint.com/personal/nguyenducanh_memoryosvadan_onmicrosoft_com/Documents");
        assertTrue(personal.personalSite());
        assertEquals("Documents", personal.librarySegment());

        // A sharing link carries the real path after its ":f:/r" prefix.
        var shared = SharePointUrl.parse(
                "https://contoso.sharepoint.com/:f:/r/sites/Finance/Shared%20Documents/Reports?csf=1&web=1&e=abc");
        assertEquals(SharePointUrl.Kind.FOLDER, shared.kind());
        assertEquals("/sites/Finance/Shared Documents/Reports", shared.path());

        // A library view address points at the library, not at a folder called Forms.
        var view = SharePointUrl.parse("https://contoso.sharepoint.com/sites/Finance/Shared%20Documents/Forms/AllItems.aspx");
        assertEquals(SharePointUrl.Kind.LIBRARY, view.kind());
        assertEquals("Shared Documents", view.librarySegment());
    }

    @Test
    void rejectsAddressesThatAreNotSharePointRoots() {
        assertRejected("http://contoso.sharepoint.com/sites/Finance", "plain http");
        assertRejected("https://contoso.example.com/sites/Finance", "another host");
        assertRejected("https://contoso.sharepoint.com/", "no site");
        assertRejected("https://contoso.sharepoint.com/search", "not a site path");
        assertRejected("https://user:secret@contoso.sharepoint.com/sites/Finance", "credentials in the address");
        assertRejected("https://contoso.sharepoint.com/sites/Finance/" + "a".repeat(300), "an unusable segment");
        assertRejected("", "empty");
        assertRejected(null, "null");
    }

    @Test
    void coversDetectsOverlappingRoots() {
        var site = SharePointUrl.parse("https://contoso.sharepoint.com/sites/Finance");
        var library = SharePointUrl.parse("https://contoso.sharepoint.com/sites/Finance/Shared%20Documents");
        var folder = SharePointUrl.parse("https://contoso.sharepoint.com/sites/Finance/Shared%20Documents/Reports");
        var nested = SharePointUrl.parse("https://contoso.sharepoint.com/sites/Finance/Shared%20Documents/Reports/2026");
        var otherLibrary = SharePointUrl.parse("https://contoso.sharepoint.com/sites/Finance/Policies");
        var otherSite = SharePointUrl.parse("https://contoso.sharepoint.com/sites/People");

        assertTrue(site.covers(library));
        assertTrue(site.covers(folder));
        assertTrue(library.covers(folder));
        assertTrue(folder.covers(nested));
        assertTrue(folder.covers(folder));
        assertFalse(folder.covers(library));
        assertFalse(library.covers(otherLibrary));
        assertFalse(site.covers(otherSite));
        // SharePoint paths are case-insensitive, so a differently cased address is the same root.
        assertTrue(library.covers(SharePointUrl.parse("https://contoso.sharepoint.com/sites/finance/shared%20documents/Reports")));
    }

    private static void assertRejected(String value, String reason) {
        var exception = assertThrows(SharePointException.class, () -> SharePointUrl.parse(value), reason);
        assertEquals("SOURCE_SHAREPOINT_ROOT_URL_INVALID", exception.code(), reason);
    }
}
