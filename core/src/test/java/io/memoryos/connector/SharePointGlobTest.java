package io.memoryos.connector;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SharePointGlobTest {

    @Test
    void matchesShellStyleWildcardsAcrossSeparators() {
        assertTrue(SharePointGlob.of("*.pdf").matches("/sites/Finance/Shared Documents/report.pdf"));
        assertTrue(SharePointGlob.of("/sites/Finance/*").matches("/sites/Finance/Shared Documents/Reports/2026/q1.docx"));
        assertTrue(SharePointGlob.of("draft?.docx").matches("draft1.docx"));
        assertFalse(SharePointGlob.of("draft?.docx").matches("draft12.docx"));
        assertFalse(SharePointGlob.of("*.pdf").matches("report.pdf.txt"));
    }

    @Test
    void ignoresCaseIncludingNonAsciiNames() {
        assertTrue(SharePointGlob.of("*/Tài liệu/*").matches("/sites/MemoryOSVi/TÀI LIỆU/baocao.xlsx"));
        assertTrue(SharePointGlob.of("/SITES/finance").matches("/sites/Finance"));
    }

    @Test
    void treatsRegularExpressionCharactersLiterally() {
        assertTrue(SharePointGlob.of("report (final).pdf").matches("report (final).pdf"));
        assertFalse(SharePointGlob.of("report (final).pdf").matches("report final.pdf"));
        assertTrue(SharePointGlob.of("a+b.txt").matches("a+b.txt"));
        assertFalse(SharePointGlob.of("a+b.txt").matches("aab.txt"));
    }

    @Test
    void excludedAppliesTheWholeList() {
        var patterns = SharePointGlob.all(List.of("*.tmp", "*/Archive/*"));
        assertTrue(SharePointGlob.excluded(patterns, "/sites/Finance/Shared Documents/notes.tmp"));
        assertTrue(SharePointGlob.excluded(patterns, "/sites/Finance/Shared Documents/Archive/old.docx"));
        assertFalse(SharePointGlob.excluded(patterns, "/sites/Finance/Shared Documents/current.docx"));
        assertFalse(SharePointGlob.excluded(List.of(), "/sites/Finance/Shared Documents/current.docx"));
    }

    @Test
    void rejectsEmptyOversizedAndTooManyPatterns() {
        assertInvalid(() -> SharePointGlob.of(" "));
        assertInvalid(() -> SharePointGlob.of(null));
        assertInvalid(() -> SharePointGlob.of("x".repeat(SharePointGlob.MAX_PATTERN_CHARS + 1)));
        var many = IntStream.range(0, SharePointGlob.MAX_PATTERNS + 1).mapToObj(index -> "pattern" + index).toList();
        assertInvalid(() -> SharePointGlob.all(many));
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable call) {
        assertEquals("SOURCE_SHAREPOINT_EXCLUSION_INVALID",
                assertThrows(SharePointException.class, call).code());
    }
}
