package io.memoryos.provider.file;

import io.memoryos.document.ExtractedDocument.Page;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * What one PDFBox pass learns about a PDF before it is sent anywhere: its pages as displayed, and
 * whether it carries a text layer. Admission (encryption, page limit) stays with the caller that
 * loaded the document.
 *
 * @param pages the page count
 * @param sizes each page's CropBox in points, width and height swapped for a page turned by
 *              {@code /Rotate} 90 or 270, because that is the page a renderer draws
 * @param scanned the text layer holds fewer than {@link DoclingSourceContentExtractor#MIN_FALLBACK_CHARACTERS_PER_PDF_PAGE}
 *                non-whitespace characters a page; false when the density was not measured
 */
record PdfLayout(int pages, List<Page> sizes, boolean scanned) {
    PdfLayout {
        sizes = List.copyOf(sizes);
    }

    static PdfLayout of(PDDocument pdf, boolean measureTextLayer) throws IOException {
        int pages = pdf.getNumberOfPages();
        var sizes = new ArrayList<Page>(pages);
        for (int index = 0; index < pages; index++) {
            var page = pdf.getPage(index);
            var box = page.getCropBox();
            int rotation = Math.floorMod(page.getRotation(), 360);
            boolean turned = rotation == 90 || rotation == 270;
            sizes.add(new Page(index + 1, turned ? box.getHeight() : box.getWidth(),
                    turned ? box.getWidth() : box.getHeight()));
        }
        return new PdfLayout(pages, sizes, measureTextLayer && scanned(pdf, pages));
    }

    /**
     * A document is read as a scan when any one page has too little text layer to be anything else.
     * Docling reads text layers only once PaddleOCR-VL exists, so a report whose narrative pages carry
     * text and whose statements are scanned would otherwise lose those statements while succeeding.
     * A text document with a blank or picture page goes to PaddleOCR-VL as well; that costs GPU time,
     * not content. Reads page by page and stops at the first such page.
     */
    private static boolean scanned(PDDocument pdf, int pages) throws IOException {
        var stripper = new PDFTextStripper();
        for (int page = 1; page <= pages; page++) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("text layer measurement interrupted");
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            long characters = stripper.getText(pdf).codePoints().filter(c -> !Character.isWhitespace(c)).count();
            if (characters < DoclingSourceContentExtractor.MIN_FALLBACK_CHARACTERS_PER_PDF_PAGE) return true;
        }
        return false;
    }
}
