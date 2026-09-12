package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.provider.SourceContentExtractorRouter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class SpreadsheetSourceContentExtractorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SpreadsheetSourceContentExtractor reader = new SpreadsheetSourceContentExtractor(mapper);
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;

    @Test
    void sourceAndChatShareWorkbookContractIncludingDatesAndSearchableCells() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            workbook.getCTWorkbook().getWorkbookPr().setDate1904(true);
            var sheet = workbook.createSheet("Doanh thu");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(0);
            row.createCell(1).setCellValue(false);
            var date = row.createCell(2);
            date.setCellValue(1);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(style);
            var formula = row.createCell(3);
            formula.setCellFormula("100+20"); formula.setCellValue(127);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));
            workbook.createSheet("Hidden"); workbook.setSheetHidden(1, true);
            workbook.write(out); bytes = out.toByteArray();
        }
        var source = reader.extract(bytes, "book.xlsx", SpreadsheetSourceContentExtractor.XLSX, SourceInputDescriptor.binary());
        var chat = new BoundedChatFileExtractor(mock(DoclingSourceContentExtractor.class), mapper)
                .extract(new ByteArrayInputStream(bytes), bytes.length, "book.xlsx");
        assertEquals(source.structuredJson(), chat.structuredJson());
        assertEquals(source.normalizedText(), chat.normalizedText());
        assertTrue(chat.normalizedText().contains("1904-01-02"));
        var chunks = new io.memoryos.document.application.StructuredDocumentChunker(mapper).chunk(chat.title(), chat.structuredJson());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("[D1] 127")));
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.content().contains("100+20")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.provenanceJson().contains("Doanh thu")));
    }

    @Test
    void sourceAndChatShareDelimitedContractAndProduceSearchableValues() throws Exception {
        byte[] bytes = "\uFEFF\"name\",value\r\n\"multi\nline\",\"a,\"\"b\"\"\"\r\nformula,=1+1\r\n".getBytes(StandardCharsets.UTF_8);
        var source = reader.extract(bytes, "data.csv", "text/csv", SourceInputDescriptor.binary());
        var chat = new BoundedChatFileExtractor(mock(DoclingSourceContentExtractor.class), mapper)
                .extract(new ByteArrayInputStream(bytes), bytes.length, "data.csv");
        assertEquals(source.structuredJson(), chat.structuredJson());
        assertEquals(source.normalizedText(), chat.normalizedText());
        assertTrue(new io.memoryos.document.application.StructuredDocumentChunker(mapper).chunk(chat.title(), chat.structuredJson())
                .stream().anyMatch(chunk -> chunk.content().contains("[B3] =1+1")));
    }

    @Test
    void diskReaderPreservesArchiveIntegrityChecksAndBoundsMalformedRecords() throws Exception {
        var archive = temporary.resolve("corrupt.xlsx");
        byte[] bytes = zip64Workbook();
        var zip = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int checksum = zip.getInt(bytes.length - 6) + 16;
        zip.putInt(checksum, zip.getInt(checksum) ^ 1);
        java.nio.file.Files.write(archive, bytes);
        assertEquals(ExtractionFailure.MALFORMED, assertThrows(ExtractionException.class,
                () -> reader.extractFile(archive, "corrupt.xlsx", SpreadsheetSourceContentExtractor.XLSX)).failure());
        var csv = temporary.resolve("large.csv");
        java.nio.file.Files.writeString(csv, "\"" + "x".repeat(4_100_000));
        assertEquals(ExtractionFailure.WRITE_LIMIT, assertThrows(ExtractionException.class,
                () -> reader.extractFile(csv, "large.csv", "text/csv")).failure());
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 24})
    void xlsxRejectsCorruptedCentralDirectoryChecksumOrSize(int fieldOffset) throws Exception {
        byte[] bytes = zip64Workbook();
        var archive = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int centralDirectory = archive.getInt(bytes.length - 6);
        int field = centralDirectory + fieldOffset;
        archive.putInt(field, archive.getInt(field) ^ 1);
        assertEquals(ExtractionFailure.MALFORMED, assertThrows(ExtractionException.class,
                () -> reader.extract(bytes, "corrupt.xlsx", SpreadsheetSourceContentExtractor.XLSX,
                        SourceInputDescriptor.binary())).failure());
    }

    @Test
    void csvRouterParsesBomQuotedNewlinesEscapedQuotesAndFormulaLikeTextAsCells() throws Exception {
        byte[] bytes = "\ufeffname,value\r\n\"multi\nline\",\"a,\"\"b\"\"\"\r\nformula,=1+1\r\n".getBytes(StandardCharsets.UTF_8);
        var router = new SourceContentExtractorRouter(mock(DoclingSourceContentExtractor.class), mapper);
        var result = router.extract(new ByteArrayInputStream(bytes), bytes.length, "upload.csv", SourceInputDescriptor.binary());
        var table = mapper.readTree(result.structuredJson()).path("blocks").get(0).path("table");
        assertEquals(3, table.path("rowCount").asInt());
        assertEquals(2, table.path("columnCount").asInt());
        assertEquals("multi\nline", table.path("cells").get(2).path("text").asString());
        assertEquals("a,\"b\"", table.path("cells").get(3).path("text").asString());
        assertEquals("=1+1", table.path("cells").get(5).path("value").asString());
    }

    @Test
    void xlsxRetainsZeroFalseSparseCoordinatesMergeAndCachedFormulaWithoutEvaluation() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Data");
            sheet.createRow(0).createCell(0).setCellValue(0);
            sheet.getRow(0).createCell(1).setCellValue(false);
            var formula = sheet.createRow(2).createCell(2);
            formula.setCellFormula("1+1");
            formula.setCellValue(127);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));
            workbook.createSheet("Hidden");
            workbook.setSheetHidden(1, true);
            workbook.write(out);
            bytes = out.toByteArray();
        }
        var result = reader.extract(bytes, "book.xlsx", SpreadsheetSourceContentExtractor.XLSX, SourceInputDescriptor.binary());
        var blocks = mapper.readTree(result.structuredJson()).path("blocks");
        var cells = blocks.get(0).path("table").path("cells");
        assertEquals("0", cells.get(0).path("text").asString());
        assertEquals("false", cells.get(1).path("text").asString());
        assertEquals("1+1", cells.get(2).path("formula").asString());
        assertEquals("127", cells.get(2).path("text").asString());
        assertEquals("C3", cells.get(2).path("address").asString());
        assertEquals("A2:B2", blocks.get(0).path("table").path("merges").get(0).path("range").asString());
        assertEquals("HIDDEN", blocks.get(1).path("provenance").path("visibility").asString());
    }

    @Test
    void xlsxReadsSmallWorkbookWithStreamingZip64DataDescriptors() throws Exception {
        byte[] bytes = zip64Workbook();
        var result = reader.extract(bytes, "export.xlsx", SpreadsheetSourceContentExtractor.XLSX,
                SourceInputDescriptor.binary());
        var cells = mapper.readTree(result.structuredJson()).path("blocks").get(0).path("table").path("cells");
        assertEquals("Biển đảo", cells.get(0).path("text").asString());
        assertEquals("7", cells.get(1).path("text").asString());
    }

    @Test
    void csvRejectsMalformedUtf8UnclosedQuotesAndNormalizedOutputOverflow() {
        assertEquals(ExtractionFailure.MALFORMED, assertThrows(ExtractionException.class,
                () -> reader.extract(new byte[]{(byte) 0xc3, 0x28}, "bad.csv", "text/csv", SourceInputDescriptor.binary())).failure());
        assertEquals(ExtractionFailure.MALFORMED, assertThrows(ExtractionException.class,
                () -> reader.extract("\"unclosed".getBytes(StandardCharsets.UTF_8), "bad.csv", "text/csv", SourceInputDescriptor.binary())).failure());
        assertEquals(ExtractionFailure.WRITE_LIMIT, assertThrows(ExtractionException.class,
                () -> reader.extract("x".repeat(2_000_001).getBytes(StandardCharsets.UTF_8), "large.csv", "text/csv", SourceInputDescriptor.binary())).failure());
    }

    @Test
    void xlsxRejectsDecompressionBombAndHugeSparseDimensions() throws Exception {
        byte[] bomb;
        try (var out = new ByteArrayOutputStream(); var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("xl/media/bomb.bin"));
            zip.write(new byte[2_000_000]);
            zip.closeEntry();
            zip.finish();
            bomb = out.toByteArray();
        }
        assertEquals(ExtractionFailure.WRITE_LIMIT, assertThrows(ExtractionException.class,
                () -> reader.extract(bomb, "bomb.xlsx", SpreadsheetSourceContentExtractor.XLSX, SourceInputDescriptor.binary())).failure());
        byte[] sparse;
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            workbook.createSheet("Sparse").createRow(200_000).createCell(0).setCellValue("late");
            workbook.write(out);
            sparse = out.toByteArray();
        }
        assertEquals(ExtractionFailure.WRITE_LIMIT, assertThrows(ExtractionException.class,
                () -> reader.extract(sparse, "sparse.xlsx", SpreadsheetSourceContentExtractor.XLSX, SourceInputDescriptor.binary())).failure());
    }

    private static byte[] zip64Workbook() throws Exception {
        // Synthetic OOXML with 64-bit streaming descriptors and zero local-header sizes.
        try (var input = SpreadsheetSourceContentExtractorTest.class.getResourceAsStream("/streaming-zip64.xlsx")) {
            return java.util.Objects.requireNonNull(input).readAllBytes();
        }
    }
}
