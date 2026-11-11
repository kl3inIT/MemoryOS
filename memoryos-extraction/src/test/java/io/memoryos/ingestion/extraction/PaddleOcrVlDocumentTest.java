package io.memoryos.ingestion.extraction;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.BoundingBox;
import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.CoordOrigin;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.document.ExtractedDocument.Line;
import io.memoryos.document.ExtractedDocument;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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
        // Paddle writes only <td>; the two rows above the first amount are the measured header.
        assertEquals(List.of("0,0", "0,1", "0,2", "0,3", "0,5", "1,3", "1,4", "1,5", "1,6"), table.cells().stream()
                .filter(Cell::columnHeader).map(cell -> cell.row() + "," + cell.column()).toList());
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
        // The statement title right above the table names it; on the copy it repeats and stays a
        // paragraph, as do the address line and the unit note.
        var titles = blocks.stream().filter(block -> block.text().startsWith("BÁO CÁO TÌNH HÌNH TÀI CHÍNH")).toList();
        assertEquals(List.of(Kind.HEADING, Kind.PARAGRAPH), titles.stream().map(Block::kind).toList());
        assertEquals(2, titles.getFirst().headingLevel());
        assertTrue(blocks.stream().anyMatch(block -> block.kind() == Kind.PARAGRAPH && block.text().startsWith("Tăng 1 và Tăng 20")));
        assertTrue(blocks.stream().anyMatch(block -> block.kind() == Kind.PARAGRAPH && block.text().equals("Đơn vị tính: VND")));
        assertTrue(blocks.stream().noneMatch(block -> block.text().equals("4")), "page number");
    }

    @Test
    void aPageTurnedByRotateIsMeasuredAndBoxedAsItIsDisplayed() throws Exception {
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

            // The sideways fixture's first page, stored portrait with /Rotate 90, renders landscape: its
            // boxes are the pixel boxes of the 1685 x 1191 rendering in points of the displayed page.
            var blocks = PaddleOcrVlDocument.blocks(results("sideways-and-upright-page.json"), layout.frames().subList(0, 2));
            var turnedTable = blocks.stream().filter(block -> block.kind() == Kind.TABLE).toList().get(0);
            assertEquals(1, turnedTable.locations().getFirst().pageNo());
            assertFramed(259 * A4_LONG / 1685, 91 * A4_SHORT / 1191, 1005 * A4_LONG / 1685, 1032 * A4_SHORT / 1191,
                    A4_LONG, A4_SHORT, Objects.requireNonNull(turnedTable.locations().getFirst().bbox()));
            var upright = blocks.stream().filter(block -> block.kind() == Kind.TABLE).toList().get(1);
            assertEquals(2, upright.locations().getFirst().pageNo());
            assertBox(158 * A4_SHORT / 1191, A4_LONG - 270 * A4_LONG / 1685, 1100 * A4_SHORT / 1191,
                    A4_LONG - 1000 * A4_LONG / 1685, Objects.requireNonNull(upright.locations().getFirst().bbox()));
        }
    }

    @Test
    void aTurnedPageKeepsItsBoxAtEveryRightAngleAndIgnoresTheCropBoxCorner() throws Exception {
        String answer = """
                [{"prunedResult":{"width":%d,"height":%d,"model_settings":{"use_doc_preprocessor":false},
                  "parsing_res_list":[{"block_label":"text","block_content":"Tổng cộng","block_bbox":[100,200,300,400]}]}}]
                """;
        // A 500 x 1000 pt CropBox at (36, 48): displayed 1000 x 500 for a quarter turn, rendered at twice its points.
        for (int rotation : List.of(90, 270)) {
            var blocks = PaddleOcrVlDocument.blocks(mapper.readTree(answer.formatted(2000, 1000)),
                    List.of(new PdfLayout.Frame(36, 48, 500, 1000, rotation)));
            assertFramed(50, 100, 150, 200, 1000, 500, Objects.requireNonNull(blocks.getFirst().locations().getFirst().bbox()));
        }
        var half = PaddleOcrVlDocument.blocks(mapper.readTree(answer.formatted(1000, 2000)),
                List.of(new PdfLayout.Frame(36, 48, 500, 1000, 180)));
        assertFramed(50, 100, 150, 200, 500, 1000, Objects.requireNonNull(half.getFirst().locations().getFirst().bbox()));
    }

    @Test
    void aTurnedPageAnsweredInAnotherFrameKeepsItsPageAlone() throws Exception {
        String answer = """
                [{"prunedResult":{"width":%d,"height":%d,%s
                  "parsing_res_list":[{"block_label":"text","block_content":"Tổng cộng","block_bbox":[100,200,300,400]}]}}]
                """;
        String asSent = "\"model_settings\":{\"use_doc_preprocessor\":false},";
        String corrected = "\"model_settings\":{\"use_doc_preprocessor\":true},";
        var quarter = List.of(new PdfLayout.Frame(0, 0, 500, 1000, 90));
        var half = List.of(new PdfLayout.Frame(0, 0, 500, 1000, 180));
        // The stored page's proportions, not the displayed page's: the rendering did not apply /Rotate.
        assertNull(onlyBox(answer.formatted(1000, 2000, asSent), quarter));
        // The preprocessor ran: the answer is in a page it turned or unwarped.
        assertNull(onlyBox(answer.formatted(2000, 1000, corrected), quarter));
        assertNull(onlyBox(answer.formatted(1000, 2000, corrected), half));
        // An answer that does not say: a quarter turn is told by its proportions, a half turn by nothing.
        assertNotNull(onlyBox(answer.formatted(2000, 1000, ""), quarter));
        assertNull(onlyBox(answer.formatted(1000, 2000, ""), half));
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
    void anImageKeepsEachBoxInThePixelsOfThePictureItWasReadIn() throws Exception {
        var blocks = PaddleOcrVlDocument.blocks(results("financial-statement-page.json"), List.of());

        assertFalse(blocks.isEmpty());
        assertTrue(blocks.stream().allMatch(block -> block.locations().size() == 1
                && block.locations().getFirst().pageNo() == 1));
        // block_bbox [158, 286, 1520, 876] of a picture the service read as 1685 x 1191.
        assertFramed(158, 286, 1520, 876, 1685, 1191, Objects.requireNonNull(table(blocks).locations().getFirst().bbox()));
        assertTrue(blocks.stream().allMatch(block -> block.locations().getFirst().bbox() != null));
    }

    @Test
    void eachFrameOfAMultiPageImageIsAPageWithItsOwnFrame() throws Exception {
        var blocks = PaddleOcrVlDocument.blocks(results("sideways-and-upright-page.json"), List.of());

        assertEquals(Set.of(1, 2), blocks.stream().map(block -> block.locations().getFirst().pageNo())
                .collect(Collectors.toSet()));
        var tables = blocks.stream().filter(block -> block.kind() == Kind.TABLE).toList();
        assertFramed(259, 91, 1005, 1032, 1685, 1191, Objects.requireNonNull(tables.get(0).locations().getFirst().bbox()));
        assertFramed(158, 270, 1100, 1000, 1191, 1685, Objects.requireNonNull(tables.get(1).locations().getFirst().bbox()));
    }

    @Test
    void anImageBoxIsKeptOnlyWhenTheAnswerSaysThePictureWasReadAsSent() throws Exception {
        // The preprocessor turns or unwarps the picture and answers in the result; an answer that does
        // not say whether it ran is not known to be in the picture a reader sees.
        for (String settings : List.of("\"model_settings\":{\"use_doc_preprocessor\":true},", "")) {
            var blocks = PaddleOcrVlDocument.blocks(mapper.readTree("""
                    [{"prunedResult":{"width":1000,"height":2000,%s"parsing_res_list":[
                      {"block_label":"text","block_content":"Tổng cộng","block_bbox":[100,200,300,400]}]}}]
                    """.formatted(settings)), List.of());

            assertEquals(1, blocks.getFirst().locations().getFirst().pageNo());
            assertNull(blocks.getFirst().locations().getFirst().bbox(), settings);
        }
    }

    @Test
    void anImageBoxOutsideThePictureOrWithoutAreaIsDroppedAndOneOnItsEdgeIsHeldToIt() throws Exception {
        var blocks = PaddleOcrVlDocument.blocks(mapper.readTree("""
                [{"prunedResult":{"width":1000,"height":2000,"model_settings":{"use_doc_preprocessor":false},
                  "parsing_res_list":[
                  {"block_label":"text","block_content":"ngoài ảnh","block_bbox":[900,200,1300,400]},
                  {"block_label":"text","block_content":"không có diện tích","block_bbox":[100,200,100,400]},
                  {"block_label":"text","block_content":"sát mép","block_bbox":[-0.5,1990,300,2000.6]},
                  {"block_label":"text","block_content":"góc đảo","block_bbox":[300,400,100,200]}]}}]
                """), List.of());

        assertNull(blocks.get(0).locations().getFirst().bbox());
        assertNull(blocks.get(1).locations().getFirst().bbox());
        assertFramed(0, 1990, 300, 2000, 1000, 2000, Objects.requireNonNull(blocks.get(2).locations().getFirst().bbox()));
        assertFramed(100, 200, 300, 400, 1000, 2000, Objects.requireNonNull(blocks.get(3).locations().getFirst().bbox()));
    }

    @Test
    void aTiltedOrCurvedBlockOfAPictureKeepsItsOutlineInThePicturesPixels() throws Exception {
        // layoutShapeMode "auto": a quadrilateral for a tilted line, a polygon for a curved heading,
        // the box alone for an upright paragraph (its outline is the box's own corners).
        var blocks = PaddleOcrVlDocument.blocks(mapper.readTree("""
                [{"prunedResult":{"width":1000,"height":800,"model_settings":{"use_doc_preprocessor":false},
                  "parsing_res_list":[
                  {"block_label":"text","block_content":"dòng chéo","block_bbox":[100,100,700,400],
                   "block_polygon_points":[[100,340],[650,100],[700,160],[150,400]]},
                  {"block_label":"paragraph_title","block_content":"chữ cong","block_bbox":[200,500,800,700],
                   "block_polygon_points":[[200,700],[300,560],[500,500],[700,560],[800,700],[700,640],[500,590],[300,640]]},
                  {"block_label":"text","block_content":"đoạn thẳng","block_bbox":[100,720,900,790],
                   "block_polygon_points":[[100,720],[900,720],[900,790],[100,790]]}]}}]
                """), List.of());

        var tilted = Objects.requireNonNull(blocks.get(0).locations().getFirst().bbox());
        assertFramed(100, 100, 700, 400, 1000, 800, tilted);
        assertEquals(List.of(List.of(100.0, 340.0), List.of(650.0, 100.0), List.of(700.0, 160.0),
                List.of(150.0, 400.0)), tilted.polygon());
        var curved = Objects.requireNonNull(blocks.get(1).locations().getFirst().bbox());
        assertEquals(8, Objects.requireNonNull(curved.polygon()).size());
        assertEquals(List.of(500.0, 500.0), curved.polygon().get(2));
        assertNull(Objects.requireNonNull(blocks.get(2).locations().getFirst().bbox()).polygon());
    }

    @Test
    void anOutlineOnAnUprightPdfPageIsInUserSpaceLikeItsBox() throws Exception {
        // A 1000 x 500 rendering of a 500 x 250 pt page: half a point a pixel, y up from the bottom.
        var box = Objects.requireNonNull(onlyBox("""
                [{"prunedResult":{"width":1000,"height":500,"parsing_res_list":[
                  {"block_label":"text","block_content":"dòng chéo","block_bbox":[100,100,500,300],
                   "block_polygon_points":[[100,260],[460,100],[500,140],[140,300]]}]}}]
                """, List.of(upright(500, 250))));

        assertBox(50, 200, 250, 100, box);
        assertEquals(List.of(List.of(50.0, 120.0), List.of(230.0, 200.0), List.of(250.0, 180.0),
                List.of(70.0, 100.0)), box.polygon());
    }

    @Test
    void eachSpottedLineOfAPictureGoesToTheTextBlockThatHoldsItInThePicturesPixels() throws Exception {
        var layout = mapper.readTree("""
                [{"prunedResult":{"width":1000,"height":800,"model_settings":{"use_doc_preprocessor":false},
                  "parsing_res_list":[
                  {"block_label":"header","block_content":"Đầu trang","block_bbox":[0,0,1000,60]},
                  {"block_label":"text","block_content":"Dòng một của đoạn Dòng hai","block_bbox":[100,100,900,400]},
                  {"block_label":"paragraph_title","block_content":"Tiêu đề","block_bbox":[100,450,900,520]},
                  {"block_label":"table","block_content":"<table><tr><td>Ô bảng</td><td>1.234.567</td></tr></table>",
                   "block_bbox":[100,550,900,780]}]}}]
                """);
        var spotting = mapper.readTree("""
                [{"prunedResult":{"width":1000,"height":800,"model_settings":{"use_doc_preprocessor":false},
                  "spotting_res":{"rec_texts":["Đầu trang","Dòng một của đoạn","  Dòng hai ","Tiêu đề","Ô bảng","",
                    "ba góc","ngoài trang","không có diện tích"],
                  "rec_polys":[
                   [[10,10],[400,10],[400,50],[10,50]],
                   [[110,110],[890,110],[890,150],[110,150]],
                   [[110,160],[600,160],[600,200],[110,200]],
                   [[110,460],[500,460],[500,510],[110,510]],
                   [[120,560],[300,560],[300,600],[120,600]],
                   [[110,210],[600,210],[600,250],[110,250]],
                   [[110,260],[600,260],[600,300]],
                   [[110,310],[1200,310],[1200,350],[110,350]],
                   [[110,360],[600,360],[600,360],[110,360]]]}}}]
                """);
        var blocks = PaddleOcrVlDocument.blocks(layout, List.of());

        var lines = PaddleOcrVlDocument.lines(spotting, List.of(), blocks);

        // The running header has no block, a table's cell is not a text line, and an empty, three-cornered,
        // off-page or flat line is no line.
        assertEquals(List.of("Dòng một của đoạn", "Dòng hai", "Tiêu đề"), lines.stream().map(Line::text).toList());
        assertEquals(List.of(0, 0, 1), lines.stream().map(Line::block).toList());
        assertTrue(lines.stream().allMatch(line -> line.pageNo() == 1));
        var first = lines.getFirst().bbox();
        assertFramed(110, 110, 890, 150, 1000, 800, first);
        assertEquals(List.of(List.of(110.0, 110.0), List.of(890.0, 110.0), List.of(890.0, 150.0),
                List.of(110.0, 150.0)), first.polygon());
    }

    @Test
    void aLineOnAnUprightPdfPageIsInUserSpaceAndGoesToTheSmallestBlockHoldingItsCentre() throws Exception {
        // A 1000 x 500 rendering of a 500 x 250 pt page; the spotting answer read the page at twice that size.
        var layout = mapper.readTree("""
                [{"prunedResult":{"width":1000,"height":500,"parsing_res_list":[
                  {"block_label":"text","block_content":"đoạn lớn","block_bbox":[100,100,900,300]},
                  {"block_label":"text","block_content":"đoạn nhỏ","block_bbox":[100,200,500,300]},
                  {"block_label":"text","block_content":"dòng chéo","block_bbox":[600,320,900,480],
                   "block_polygon_points":[[600,480],[880,320],[900,340],[620,480]]}]}}]
                """);
        var spotting = mapper.readTree("""
                [{"prunedResult":{"width":2000,"height":1000,"spotting_res":{
                  "rec_texts":["dòng của đoạn lớn","dòng của đoạn nhỏ","ngoài nét chéo"],
                  "rec_polys":[
                   [[240,240],[1760,240],[1760,320],[240,320]],
                   [[240,440],[960,440],[960,560],[240,560]],
                   [[1220,660],[1400,660],[1400,700],[1220,700]]]}}}]
                """);
        var pages = List.of(upright(500, 250));

        var lines = PaddleOcrVlDocument.lines(spotting, pages, PaddleOcrVlDocument.blocks(layout, pages));

        // The tilted block's box holds the third line's centre, its outline does not.
        assertEquals(List.of(0, 1), lines.stream().map(Line::block).toList());
        var line = lines.getFirst().bbox();
        assertBox(60, 190, 440, 170, line);
        assertEquals(List.of(List.of(60.0, 190.0), List.of(440.0, 190.0), List.of(440.0, 170.0),
                List.of(60.0, 170.0)), line.polygon());
    }

    @Test
    void anAnswerWithoutUsableLinesLocatesNone() throws Exception {
        var layout = mapper.readTree("""
                [{"prunedResult":{"width":1000,"height":800,"model_settings":{"use_doc_preprocessor":false},
                  "parsing_res_list":[{"block_label":"text","block_content":"đoạn","block_bbox":[100,100,900,400]}]}}]
                """);
        var blocks = PaddleOcrVlDocument.blocks(layout, List.of());
        String line = "\"rec_texts\":[\"đoạn\"],\"rec_polys\":[[[110,110],[890,110],[890,150],[110,150]]]";

        for (String answer : List.of(
                "[{\"prunedResult\":{\"width\":1000,\"height\":800,\"model_settings\":{\"use_doc_preprocessor\":false}}}]",
                // the picture was turned or unwarped before it was read
                "[{\"prunedResult\":{\"width\":1000,\"height\":800,\"model_settings\":{\"use_doc_preprocessor\":true},"
                        + "\"spotting_res\":{" + line + "}}}]",
                // no size to place the page by
                "[{\"prunedResult\":{\"model_settings\":{\"use_doc_preprocessor\":false},\"spotting_res\":{" + line + "}}}]",
                "{\"result\":[]}")) {
            assertEquals(List.of(), PaddleOcrVlDocument.lines(mapper.readTree(answer), List.of(), blocks), answer);
        }
        // An answer for another number of pages than the PDF has is not this document's.
        assertEquals(List.of(), PaddleOcrVlDocument.lines(mapper.readTree("[{},{}]"), List.of(upright(500, 250)),
                blocks));
    }

    @Test
    void anOutlineThatCannotBeDrawnLeavesTheBoxAlone() throws Exception {
        for (String points : List.of(
                "[[100,100],[300,100]]", // too few points
                "[[100,100],[300,100],[300,\"x\"]]", // not a number
                "[[100,100],[340,100],[300,400],[100,400]]", // past the box by more than a pixel
                "[[100,100],[200,250],[300,400]]", // no area
                "[[100,100],[300,100],[300,400],[100,400]]", // the box's own corners
                "\"[[100,100],[300,100],[200,400]]\"", // text, not points
                "[" + "[100,100],".repeat(64) + "[300,400]]")) { // more than 64 points
            var box = Objects.requireNonNull(onlyBox("""
                    [{"prunedResult":{"width":1000,"height":800,"model_settings":{"use_doc_preprocessor":false},
                      "parsing_res_list":[{"block_label":"text","block_content":"đoạn","block_bbox":[100,100,300,400],
                      "block_polygon_points":%s}]}}]
                    """.formatted(points), List.of()));

            assertFramed(100, 100, 300, 400, 1000, 800, box);
            assertNull(box.polygon(), points);
        }
        // A point within a pixel of the box is held to it.
        var held = Objects.requireNonNull(onlyBox("""
                [{"prunedResult":{"width":1000,"height":800,"model_settings":{"use_doc_preprocessor":false},
                  "parsing_res_list":[{"block_label":"text","block_content":"đoạn","block_bbox":[100,100,300,400],
                  "block_polygon_points":[[99.5,380],[250,100],[300,130],[150,400.4]]}]}}]
                """, List.of()));
        assertEquals(List.of(List.of(100.0, 380.0), List.of(250.0, 100.0), List.of(300.0, 130.0),
                List.of(150.0, 400.0)), held.polygon());
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
    void aFigureTitleNamesTheTableBelowItUnlessItIsARunningTitle() throws Exception {
        // Real block texts from the HUT Q1/2026 reports: the statement title over the balance sheet,
        // a note title Paddle took for a caption, the company-and-address block Paddle also calls a
        // figure title on note pages, a signature caption, and a continuation page's title.
        var results = mapper.readTree("""
                [{"prunedResult":{"width":1000,"height":2000,"parsing_res_list":[
                  {"block_label":"header","block_content":"CÔNG TY CỔ PHẦN TASCO\\nTăng 1 và Tăng 20 Tòa nhà Tasco, Lô HH2-2, đường Phạm Hùng"},
                  {"block_label":"header","block_content":"Báo cáo tài chính hợp nhất\\nTại ngày 31 tháng 3 năm 2026"},
                  {"block_label":"figure_title","block_content":"BÁO CÁO TÌNH HÌNH TÀI CHÍNH\\nTại ngày 31 tháng 3 năm 2026"},
                  {"block_label":"vision_footnote","block_content":"Đơn vị tính: VND"},
                  {"block_label":"table","block_content":"<table><tr><td>TÀI SẢN</td><td>Số cuối kỳ</td></tr><tr><td>1. Tiền</td><td>2.470.482.178.999</td></tr></table>"},
                  {"block_label":"figure_title","block_content":"Người lập biểu"},
                  {"block_label":"chart","block_content":""}]}},
                 {"prunedResult":{"width":1000,"height":2000,"parsing_res_list":[
                  {"block_label":"figure_title","block_content":"BÁO CÁO TÌNH HÌNH TÀI CHÍNH (tiếp theo)\\nTại ngày 31 tháng 3 năm 2026"},
                  {"block_label":"table","block_content":"<table><tr><td>NGUỒN VỐN</td><td>Số cuối kỳ</td></tr><tr><td>C. NỢ PHẢI TRẢ</td><td>35.074.068.599.501</td></tr></table>"},
                  {"block_label":"figure_title","block_content":"CÔNG TY CỔ PHẦN TASCO\\nTầng 1 và Tầng 20 Tòa nhà Tasco, Lô HH2-2, đường Phạm Hùng"},
                  {"block_label":"table","block_content":"<table><tr><td></td><td>Số cuối kỳ</td></tr><tr><td>Phải trả bên thứ ba</td><td>2.279.739.559.178</td></tr></table>"},
                  {"block_label":"figure_title","block_content":"17. Người mua trả tiền trước\\n17.1 Người mua trả tiền trước ngắn hạn"},
                  {"block_label":"table","block_content":"<table><tr><td></td><td>Số cuối kỳ</td></tr><tr><td>Người mua trả tiền trước là bên thứ ba</td><td>303.914.080.645</td></tr></table>"},
                  {"block_label":"figure_title","block_content":"Công ty con trực tiếp"},
                  {"block_label":"text","block_content":"Tại ngày 31 tháng 3 năm 2026, Công ty có 5 công ty con trực tiếp."}]}}]
                """);
        var blocks = PaddleOcrVlDocument.blocks(results, List.of());

        var headings = blocks.stream().filter(block -> block.kind() == Kind.HEADING).toList();
        assertEquals(List.of("BÁO CÁO TÌNH HÌNH TÀI CHÍNH\nTại ngày 31 tháng 3 năm 2026",
                "17. Người mua trả tiền trước\n17.1 Người mua trả tiền trước ngắn hạn"),
                headings.stream().map(Block::text).toList());
        assertTrue(headings.stream().allMatch(block -> block.headingLevel() == 2), "a table title sits under the part");
        for (String paragraph : List.of("Người lập biểu", "BÁO CÁO TÌNH HÌNH TÀI CHÍNH (tiếp theo)\nTại ngày 31 tháng 3 năm 2026",
                "CÔNG TY CỔ PHẦN TASCO\nTầng 1 và Tầng 20 Tòa nhà Tasco, Lô HH2-2, đường Phạm Hùng", "Công ty con trực tiếp")) {
            assertTrue(blocks.stream().anyMatch(block -> block.kind() == Kind.PARAGRAPH && block.text().equals(paragraph)), paragraph);
        }
    }

    @Test
    void aChunkOfAPaddleBalanceSheetNamesTheStatementAndEveryValuesColumn() throws Exception {
        var results = mapper.readTree("""
                [{"prunedResult":{"width":1000,"height":2000,"parsing_res_list":[
                  {"block_label":"doc_title","block_content":"CÔNG BỐ THÔNG TIN ĐỊNH KỲ BÁO CÁO TÀI CHÍNH"},
                  {"block_label":"figure_title","block_content":"BÁO CÁO TÌNH HÌNH TÀI CHÍNH\\nTại ngày 31 tháng 3 năm 2026"},
                  {"block_label":"vision_footnote","block_content":"Đơn vị tính: VND"}]}}]
                """);
        ((tools.jackson.databind.node.ArrayNode) results.get(0).path("prunedResult").path("parsing_res_list")).addObject()
                .put("block_label", "table").put("block_content", hutTable("balance-sheet"));
        var content = DocumentAssembly.publish(mapper, PaddleOcrVlDocument.blocks(results, List.of()), List.of(), null,
                "application/pdf", "HUT Q1 2026", java.util.Map.of(), "paddleocr_vl");

        var chunks = new io.memoryos.document.application.StructuredDocumentChunker(mapper).chunk(content.title(),
                content.structuredJson());

        var cash = chunks.stream().filter(chunk -> chunk.content().contains("1. Tiền\n")).findFirst().orElseThrow();
        assertTrue(cash.content().contains("Section: CÔNG BỐ THÔNG TIN ĐỊNH KỲ BÁO CÁO TÀI CHÍNH > BÁO CÁO TÌNH HÌNH TÀI CHÍNH"),
                cash.content());
        assertTrue(cash.content().contains("TÀI SẢN: 1. Tiền\nMã\\nSố: 111\nSố cuối kỳ: 2.470.482.178.999\n"
                + "Số đầu kỳ: 2.764.761.087.606"), cash.content());
        assertFalse(cash.content().contains("[A"), "no cell addresses once the header is known");
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
    void anImageAnswerWithoutThePicturesSizeKeepsItsTextAndNoBox() throws Exception {
        var blocks = PaddleOcrVlDocument.blocks(mapper.readTree("""
                [{"prunedResult":{"model_settings":{"use_doc_preprocessor":false},"parsing_res_list":[
                  {"block_label":"text","block_content":"Tổng cộng","block_bbox":[100,200,300,400]}]}}]
                """), List.of());

        assertEquals("Tổng cộng", blocks.getFirst().text());
        assertEquals(1, blocks.getFirst().locations().getFirst().pageNo());
        assertNull(blocks.getFirst().locations().getFirst().bbox());
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

    private @Nullable BoundingBox onlyBox(String answer, List<PdfLayout.Frame> pages) throws Exception {
        var block = PaddleOcrVlDocument.blocks(mapper.readTree(answer), pages).getFirst();
        assertEquals(1, block.locations().getFirst().pageNo());
        return block.locations().getFirst().bbox();
    }

    private static void assertFramed(double l, double t, double r, double b, double width, double height,
            BoundingBox box) {
        assertEquals(l, box.l(), 0.01);
        assertEquals(t, box.t(), 0.01);
        assertEquals(r, box.r(), 0.01);
        assertEquals(b, box.b(), 0.01);
        assertEquals(CoordOrigin.TOPLEFT, box.coordOrigin());
        assertEquals(width, Objects.requireNonNull(box.frameWidth()), 0.01);
        assertEquals(height, Objects.requireNonNull(box.frameHeight()), 0.01);
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

    private static void assertFailure(ExtractionFailure expected, Executable executable) {
        assertEquals(expected, assertThrows(ExtractionException.class, executable).failure());
    }

    /** A few leading rows of a real table from the public HUT reports, as PaddleOCR-VL-1.6 returned it. */
    static String hutTable(String name) throws Exception {
        try (var input = PaddleOcrVlDocumentTest.class.getResourceAsStream("/paddleocr-vl/hut-tables.json")) {
            return new ObjectMapper().readTree(Objects.requireNonNull(input)).path(name).path("html").asString();
        }
    }

    private JsonNode results(String fixture) throws Exception {
        try (var input = getClass().getResourceAsStream("/paddleocr-vl/" + fixture)) {
            return mapper.readTree(Objects.requireNonNull(input)).path("result").path("layoutParsingResults");
        }
    }
}
