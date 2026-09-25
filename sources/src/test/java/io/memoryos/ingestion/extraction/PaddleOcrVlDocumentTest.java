package io.memoryos.ingestion.extraction;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.document.ExtractedDocument;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.BoundingBox;
import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.CoordOrigin;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MEM-192. Real PaddleOCR-VL-1.6 answers from the spike on the public HUT (Tasco) Q1/2026 report,
 * mapped onto MemoryOS blocks. Page sizes are the points PDFBox reports for the scanned pages; a
 * box is in PDF user space, y up from the CropBox's bottom edge.
 */
class PaddleOcrVlDocumentTest {
    private static final double A4_SHORT = 595.44;
    private static final double A4_LONG = 842.04;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theIncomeStatementPageKeepsItsBodyAndDropsPageFurniture() throws Exception {
        var blocks = PaddleOcrVlDocument.blocks(results("financial-statement-page.json"), List.of(upright(A4_LONG, A4_SHORT)));

        var texts = blocks.stream().map(Block::text).toList();
        assertFalse(texts.stream().anyMatch(text -> text.startsWith("CÔNG TY CỔ PHẦN TASCO")), "running header");
        assertFalse(texts.stream().anyMatch(text -> text.startsWith("Báo cáo tài chính hợp nhất")), "running header");
        assertFalse(texts.contains("ni 21 ♥4 : 25 iwn"), "footer");
        assertFalse(texts.contains("7"), "page number");
        assertEquals(List.of(Kind.PARAGRAPH, Kind.TABLE, Kind.PARAGRAPH, Kind.IMAGE, Kind.PARAGRAPH, Kind.PARAGRAPH,
                Kind.IMAGE, Kind.PARAGRAPH, Kind.PARAGRAPH, Kind.IMAGE, Kind.PARAGRAPH, Kind.PARAGRAPH),
                blocks.stream().map(Block::kind).toList(), "text, table, image and seal in Paddle's reading order");
        assertEquals("Đơn vị tính: VND", blocks.getFirst().text());
        for (int index = 0; index < blocks.size(); index++) assertEquals(index, blocks.get(index).index());
        assertTrue(blocks.stream().filter(block -> block.kind() == Kind.IMAGE).allMatch(block -> block.text().isEmpty()
                && block.image() == null), "a picture or a seal carries neither text nor an image payload");
    }

    @Test
    void aLandscapePageScalesPixelsToPointsInUserSpace() throws Exception {
        var blocks = PaddleOcrVlDocument.blocks(results("financial-statement-page.json"), List.of(upright(A4_LONG, A4_SHORT)));

        // block_bbox [158, 286, 1520, 876] on a 1685 x 1191 rendering of an 842.04 x 595.44 pt page.
        var location = table(blocks).locations().getFirst();
        assertEquals(1, location.pageNo());
        assertBox(158 * A4_LONG / 1685, A4_SHORT - 286 * A4_SHORT / 1191, 1520 * A4_LONG / 1685,
                A4_SHORT - 876 * A4_SHORT / 1191, Objects.requireNonNull(location.bbox()));
    }

    @Test
    void theHeaderSpansPlaceEveryLaterCellInItsColumn() throws Exception {
        var table = Objects.requireNonNull(table(PaddleOcrVlDocument.blocks(results("financial-statement-page.json"),
                List.of(upright(A4_LONG, A4_SHORT)))).table());

        assertEquals(24, table.rowCount());
        assertEquals(7, table.columnCount());
        assertCell(table.cells(), 0, 0, 2, 1, "CHÍ TIÊU");
        assertCell(table.cells(), 0, 3, 1, 2, "QUỀI");
        assertCell(table.cells(), 0, 5, 1, 2, "LỤY KỆ TỪ ĐAU NĂM");
        // The second header row starts under three rowspans, so its first cell is column 3, not 0.
        assertCell(table.cells(), 1, 3, 1, 1, "Năm nay");
        assertCell(table.cells(), 1, 6, 1, 1, "Năm trước");
        assertCell(table.cells(), 2, 0, 1, 1, "Doanh thu bán hàng và cung cấp dịch vụ");
        assertCell(table.cells(), 2, 3, 1, 1, "11.030.333.521.790");
        assertCell(table.cells(), 2, 4, 1, 1, "6.977.873.620.387");
        assertCell(table.cells(), 2, 5, 1, 1, "11.030.333.521.790");
        assertCell(table.cells(), 23, 1, 1, 1, "70");
        assertFalse(table.hasColumnHeaders(), "Paddle writes only <td>; no header is guessed");
        assertTrue(table.cells().stream().noneMatch(Cell::rowHeader));
    }

    @Test
    void theTableTextFollowsTheSameGridAsDocling() throws Exception {
        var blocks = PaddleOcrVlDocument.blocks(results("financial-statement-page.json"), List.of(upright(A4_LONG, A4_SHORT)));

        String text = DocumentAssembly.semanticText(blocks);
        assertTrue(text.startsWith("Đơn vị tính: VND\n\nCHÍ TIÊU\tMã số\tThuyết minh\tQUỀI\t\tLỤY KỆ TỪ ĐAU NĂM\n\t\t\tNăm nay\tNăm trước\tNăm nay\tNăm trước\n"
                + "Doanh thu bán hàng và cung cấp dịch vụ\t01\tVI.1\t11.030.333.521.790\t6.977.873.620.387\t11.030.333.521.790\t6.977.873.620.387\n"), text);
        assertTrue(text.contains("\n\nNgười lập\n\nKế toàn trường"), "pictures add no text between paragraphs");
    }

    @Test
    void aSidewaysScanAndItsUprightCopyReadTheSameTableAtTheirOwnPlaces() throws Exception {
        // Page 1 is the balance sheet scanned turned on a landscape page; page 2 is the same page upright.
        var blocks = PaddleOcrVlDocument.blocks(results("sideways-and-upright-page.json"),
                List.of(upright(A4_LONG, A4_SHORT), upright(A4_SHORT, A4_LONG)));

        var tables = blocks.stream().filter(block -> block.kind() == Kind.TABLE).toList();
        assertEquals(2, tables.size());
        assertEquals(1, tables.get(0).locations().getFirst().pageNo());
        assertEquals(2, tables.get(1).locations().getFirst().pageNo());
        assertBox(259 * A4_LONG / 1685, A4_SHORT - 91 * A4_SHORT / 1191, 1005 * A4_LONG / 1685,
                A4_SHORT - 1032 * A4_SHORT / 1191, Objects.requireNonNull(tables.get(0).locations().getFirst().bbox()));
        assertBox(158 * A4_SHORT / 1191, A4_LONG - 270 * A4_LONG / 1685, 1100 * A4_SHORT / 1191,
                A4_LONG - 1000 * A4_LONG / 1685, Objects.requireNonNull(tables.get(1).locations().getFirst().bbox()));
        assertEquals(amounts(tables.get(1)), amounts(tables.get(0)), "codes, notes and amounts agree cell for cell");
        assertEquals(23 * 4, amounts(tables.get(0)).size());
        // figure_title and vision_footnote are not titles Paddle promises; they stay paragraphs.
        assertTrue(blocks.stream().anyMatch(block -> block.kind() == Kind.PARAGRAPH
                && block.text().startsWith("BÁO CÁO TÌNH HÌNH TÀI CHÍNH")));
        assertTrue(blocks.stream().anyMatch(block -> block.kind() == Kind.PARAGRAPH && block.text().equals("Đơn vị tính: VND")));
        assertTrue(blocks.stream().noneMatch(block -> block.text().equals("4")), "page number");
    }

    @Test
    void aPageTurnedByRotateIsMeasuredAsItIsDisplayedAndCarriesNoBox() throws Exception {
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var portrait = new PDRectangle((float) A4_SHORT, (float) A4_LONG);
            var turned = new PDPage(portrait);
            turned.setRotation(90);
            pdf.addPage(turned);
            pdf.addPage(new PDPage(portrait));
            var cropped = new PDPage(new PDRectangle((float) A4_LONG, (float) A4_SHORT));
            cropped.setCropBox(new PDRectangle(10, 20, 700, 500));
            pdf.addPage(cropped);
            var negative = new PDPage(portrait);
            negative.setRotation(-90);
            pdf.addPage(negative);

            var layout = PdfLayout.of(pdf, false);
            assertEquals(4, layout.pages());
            assertPage(1, A4_LONG, A4_SHORT, layout.sizes().get(0));
            assertPage(2, A4_SHORT, A4_LONG, layout.sizes().get(1));
            assertPage(3, 700, 500, layout.sizes().get(2));
            assertPage(4, A4_LONG, A4_SHORT, layout.sizes().get(3));

            assertEquals(new PdfLayout.Frame(0, 0, (float) A4_SHORT, (float) A4_LONG, 90), layout.frames().get(0));
            assertEquals(270, layout.frames().get(3).rotation());

            // The sideways fixture's first page, stored portrait with /Rotate 90, renders landscape. The
            // viewer outlines no rotated page, so its blocks keep the page and drop the box.
            var blocks = PaddleOcrVlDocument.blocks(results("sideways-and-upright-page.json"), layout.frames().subList(0, 2));
            var onTurnedPage = blocks.stream().filter(block -> block.locations().getFirst().pageNo() == 1).toList();
            assertFalse(onTurnedPage.isEmpty());
            assertTrue(onTurnedPage.stream().allMatch(block -> block.locations().getFirst().bbox() == null));
            var upright = blocks.stream().filter(block -> block.kind() == Kind.TABLE).toList().get(1);
            assertEquals(2, upright.locations().getFirst().pageNo());
            assertBox(158 * A4_SHORT / 1191, A4_LONG - 270 * A4_LONG / 1685, 1100 * A4_SHORT / 1191,
                    A4_LONG - 1000 * A4_LONG / 1685, Objects.requireNonNull(upright.locations().getFirst().bbox()));
        }
    }

    @Test
    void aCropBoxAwayFromTheOriginPlacesTheBoxWhereTheViewerDrawsIt() throws Exception {
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(0, 0, 900, 700));
            page.setCropBox(new PDRectangle(36, 48, (float) A4_LONG, (float) A4_SHORT));
            pdf.addPage(page);

            var layout = PdfLayout.of(pdf, false);
            assertPage(1, A4_LONG, A4_SHORT, layout.sizes().getFirst());
            var frame = layout.frames().getFirst();
            assertEquals(36, frame.x0(), 0.001);
            assertEquals(48, frame.y0(), 0.001);

            var blocks = PaddleOcrVlDocument.blocks(results("financial-statement-page.json"), layout.frames());
            var box = Objects.requireNonNull(table(blocks).locations().getFirst().bbox());
            assertEquals(CoordOrigin.BOTTOMLEFT, box.coordOrigin());
            // web/src/features/preview/pdf-pages.ts pdfBoxRect with the page's view [x0, y0, x1, y1]:
            // the drawn box, relative to the CropBox's top left, is the pixel box [158, 286, 1520, 876]
            // of the 1685 x 1191 rendering scaled to points.
            double[] view = {frame.x0(), frame.y0(), frame.x0() + frame.width(), frame.y0() + frame.height()};
            double top = view[3] - Math.max(box.t(), box.b());
            double left = Math.min(box.l(), box.r()) - view[0];
            assertEquals(286 * A4_SHORT / 1191, top, 0.01);
            assertEquals(158 * A4_LONG / 1685, left, 0.01);
            assertEquals((876 - 286) * A4_SHORT / 1191, Math.abs(box.t() - box.b()), 0.01);
            assertEquals((1520 - 158) * A4_LONG / 1685, Math.abs(box.r() - box.l()), 0.01);
        }
    }

    @Test
    void anImageHasOnePageAndNoBox() throws Exception {
        var blocks = PaddleOcrVlDocument.blocks(results("financial-statement-page.json"), List.of());

        assertFalse(blocks.isEmpty());
        assertTrue(blocks.stream().allMatch(block -> block.locations().size() == 1
                && block.locations().getFirst().pageNo() == 1 && block.locations().getFirst().bbox() == null));
    }

    @Test
    void eachFrameOfAMultiPageImageIsAPageWithoutABox() throws Exception {
        var blocks = PaddleOcrVlDocument.blocks(results("sideways-and-upright-page.json"), List.of());

        assertEquals(java.util.Set.of(1, 2), blocks.stream().map(block -> block.locations().getFirst().pageNo())
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(blocks.stream().allMatch(block -> block.locations().getFirst().bbox() == null));
    }

    @Test
    void labelsMapOntoBlockKindsAndABoxMayArriveAsText() throws Exception {
        var results = mapper.readTree("""
                [{"prunedResult":{"width":1000,"height":2000,"parsing_res_list":[
                  {"block_label":"doc_title","block_content":"Báo cáo","block_bbox":"[100, 200, 300, 400]"},
                  {"block_label":"paragraph_title","block_content":"I. Tiền","block_bbox":[0,0,10,10]},
                  {"block_label":"chart","block_content":"","block_bbox":[0,0,10,10]},
                  {"block_label":"formula","block_content":"a = b","block_bbox":[0,0,10,10]},
                  {"block_label":"aside_text","block_content":"margin","block_bbox":[0,0,10,10]},
                  {"block_label":"header_image","block_content":"","block_bbox":[0,0,10,10]},
                  {"block_label":"text","block_content":"   ","block_bbox":[0,0,10,10]},
                  {"block_label":"something_new","block_content":"kept","block_bbox":"not a box"}]}}]
                """);
        var blocks = PaddleOcrVlDocument.blocks(results, List.of(upright(500, 1000)));

        assertEquals(List.of(Kind.HEADING, Kind.HEADING, Kind.IMAGE, Kind.PARAGRAPH, Kind.PARAGRAPH),
                blocks.stream().map(Block::kind).toList());
        assertEquals(1, blocks.get(0).headingLevel());
        assertEquals(2, blocks.get(1).headingLevel());
        assertBox(50, 900, 150, 800, Objects.requireNonNull(blocks.get(0).locations().getFirst().bbox()));
        assertNull(blocks.get(4).locations().getFirst().bbox());
        assertEquals(1, blocks.get(4).locations().getFirst().pageNo());
    }

    @Test
    void anAnswerThatDoesNotMatchThePdfIsMalformed() throws Exception {
        var twoPages = results("sideways-and-upright-page.json");
        assertFailure(ExtractionFailure.MALFORMED, () -> PaddleOcrVlDocument.blocks(twoPages, List.of(upright(A4_LONG, A4_SHORT))));
        assertFailure(ExtractionFailure.MALFORMED, () -> PaddleOcrVlDocument.blocks(mapper.readTree("[]"), List.of()));
        assertFailure(ExtractionFailure.MALFORMED, () -> PaddleOcrVlDocument.blocks(mapper.readTree(
                "[{\"prunedResult\":{\"width\":0,\"height\":10,\"parsing_res_list\":[]}}]"), List.of(upright(1, 1))));
        assertFailure(ExtractionFailure.MALFORMED, () -> PaddleOcrVlDocument.blocks(mapper.readTree(
                "[{\"prunedResult\":{\"width\":10,\"height\":10}}]"), List.of(upright(1, 1))));
    }

    @Test
    void aTableBeyondTheCellBoundIsAWriteLimit() {
        String row = "<tr>" + "<td>1</td>".repeat(100) + "</tr>";
        assertFailure(ExtractionFailure.WRITE_LIMIT, () -> PaddleOcrVlDocument.table("<table>" + row.repeat(2_001) + "</table>", new int[1]));
        assertFailure(ExtractionFailure.WRITE_LIMIT, () -> PaddleOcrVlDocument.table(
                "<table><tr><td colspan=\"999\">a</td><td colspan=\"2\">b</td></tr></table>", new int[1]));
    }

    private static List<String> amounts(Block table) {
        return Objects.requireNonNull(table.table()).cells().stream().filter(cell -> cell.column() > 0)
                .map(cell -> cell.row() + ":" + cell.column() + "=" + cell.text()).toList();
    }

    private static Block table(List<Block> blocks) {
        return blocks.stream().filter(block -> block.kind() == Kind.TABLE).findFirst().orElseThrow();
    }

    private static void assertCell(List<Cell> cells, int row, int column, int rowSpan, int columnSpan, String text) {
        var cell = cells.stream().filter(candidate -> candidate.row() == row && candidate.column() == column).findFirst()
                .orElseThrow(() -> new AssertionError("no cell at " + row + "," + column));
        assertEquals(text, cell.text());
        assertEquals(rowSpan, cell.rowSpan());
        assertEquals(columnSpan, cell.columnSpan());
    }

    private static void assertBox(double l, double t, double r, double b, BoundingBox box) {
        assertEquals(l, box.l(), 0.01);
        assertEquals(t, box.t(), 0.01);
        assertEquals(r, box.r(), 0.01);
        assertEquals(b, box.b(), 0.01);
        assertEquals(CoordOrigin.BOTTOMLEFT, box.coordOrigin());
    }

    /** A page at the origin with no {@code /Rotate}, as most scans are. */
    private static PdfLayout.Frame upright(double width, double height) {
        return new PdfLayout.Frame(0, 0, width, height, 0);
    }

    private static void assertPage(int number, double width, double height, ExtractedDocument.Page page) {
        assertEquals(number, page.pageNo());
        assertEquals(width, page.width(), 0.01);
        assertEquals(height, page.height(), 0.01);
    }

    private static void assertFailure(ExtractionFailure expected, org.junit.jupiter.api.function.Executable executable) {
        assertEquals(expected, assertThrows(ExtractionException.class, executable).failure());
    }

    private JsonNode results(String fixture) throws Exception {
        try (var input = getClass().getResourceAsStream("/paddleocr-vl/" + fixture)) {
            return mapper.readTree(Objects.requireNonNull(input)).path("result").path("layoutParsingResults");
        }
    }
}
