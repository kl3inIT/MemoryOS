package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.provider.SourceContentExtractorRouter;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Real OOXML with referenced PNG pictures, not padded bytes or a mocked extraction response. */
class LargeChatSpreadsheetExtractionTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(ints = {100, 250})
    void readsImageHeavyWorkbooksNearChatLimitsWithoutChangingSourceAdmission(int limitMiB) throws Exception {
        var file = temporary.resolve("large.xlsx");
        createWorkbook(file, limitMiB);
        long size = Files.size(file);
        var mapper = new ObjectMapper();
        var docling = mock(DoclingSourceContentExtractor.class);
        try (var input = Files.newInputStream(file)) {
            var parsed = new BoundedChatFileExtractor(docling, mapper).extract(input, size, "large.xlsx");
            assertTrue(parsed.normalizedText().contains("B1: 127"));
            assertTrue(parsed.structuredJson().length() < 10_000);
            assertTrue(new io.memoryos.document.application.StructuredDocumentChunker(mapper).chunk(parsed.title(), parsed.structuredJson())
                    .stream().anyMatch(chunk -> chunk.content().contains("[B1] 127")));
        }
        try (var input = Files.newInputStream(file)) {
            assertEquals(ExtractionFailure.WRITE_LIMIT, assertThrows(ExtractionException.class,
                    () -> new SourceContentExtractorRouter(docling, mapper).extract(input, size, "large.xlsx", SourceInputDescriptor.binary())).failure());
        }
        org.mockito.Mockito.verifyNoInteractions(docling);
    }

    static void createWorkbook(Path file, int limitMiB) throws Exception {
        var bitmap = new java.awt.image.BufferedImage(512, 512, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random = new java.util.Random(81);
        for (int row = 0; row < 512; row++) for (int column = 0; column < 512; column++) bitmap.setRGB(column, row, random.nextInt());
        byte[] png;
        try (var out = new ByteArrayOutputStream()) {
            assertTrue(javax.imageio.ImageIO.write(bitmap, "png", out)); png = out.toByteArray();
        } finally { bitmap.flush(); }
        long limit = limitMiB * 1024L * 1024;
        int pictures = (int) ((limit - 1024 * 1024) / png.length);
        var workbook = new XSSFWorkbook();
        try (var out = Files.newOutputStream(file)) {
            var sheet = workbook.createSheet("Báo cáo ảnh");
            sheet.createRow(0).createCell(0).setCellValue("Doanh thu đã kiểm chứng");
            sheet.getRow(0).createCell(1).setCellValue(127);
            var drawing = sheet.createDrawingPatriarch();
            for (int i = 0; i < pictures; i++) {
                int image = workbook.addPicture(png, XSSFWorkbook.PICTURE_TYPE_PNG);
                var anchor = workbook.getCreationHelper().createClientAnchor();
                anchor.setCol1(0); anchor.setCol2(4); anchor.setRow1(i + 2); anchor.setRow2(i + 3);
                drawing.createPicture(anchor, image);
            }
            workbook.write(out);
        } finally {
            // Close the package without OPCPackage.close() auto-saving a second full copy into its in-memory origin.
            workbook.getPackage().revert();
        }
        long size = Files.size(file);
        assertTrue(size > limit - 2 * 1024 * 1024 && size <= limit, "fixture must be within 2 MiB below the cap: " + size);
    }
}
