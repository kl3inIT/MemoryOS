package io.memoryos.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChatLibraryRenameTest {
    @Test
    void aNewNameKeepsTheFilesExtension() {
        assertEquals("Báo cáo quý 3.xlsx", ChatLibraryService.renamed("output_1.xlsx", "  Báo cáo quý 3 ", 200));
        assertEquals("Hợp đồng.PDF", ChatLibraryService.renamed("scan.pdf", "Hợp đồng.PDF", 200));
        assertEquals("ghi chu", ChatLibraryService.renamed("README", "ghi chu", 200));
    }

    @Test
    void refusesEmptyControlAndSlashNamesAndOverlongOnes() {
        for (var bad : new String[] {" ", "a/b", "a\\b", "tab\there", ".xlsx"})
            assertThrows(LibraryException.class, () -> ChatLibraryService.renamed("a.xlsx", bad, 200), bad);
        assertThrows(LibraryException.class, () -> ChatLibraryService.renamed("a.xlsx", "x".repeat(196), 200));
    }
}
