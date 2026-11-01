package io.memoryos.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LibraryRenameTest {
    @Test
    void aNewNameKeepsTheFilesExtension() {
        assertEquals("Báo cáo quý 3.xlsx", LibraryService.renamed("output_1.xlsx", "  Báo cáo quý 3 ", 200));
        assertEquals("Hợp đồng.PDF", LibraryService.renamed("scan.pdf", "Hợp đồng.PDF", 200));
        assertEquals("ghi chu", LibraryService.renamed("README", "ghi chu", 200));
    }

    @Test
    void refusesEmptyControlAndSlashNamesAndOverlongOnes() {
        for (var bad : new String[] {" ", "a/b", "a\\b", "tab\there", ".xlsx"})
            assertThrows(LibraryException.class, () -> LibraryService.renamed("a.xlsx", bad, 200), bad);
        assertThrows(LibraryException.class, () -> LibraryService.renamed("a.xlsx", "x".repeat(196), 200));
    }
}
