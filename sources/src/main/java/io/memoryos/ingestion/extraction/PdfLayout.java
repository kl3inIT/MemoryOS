package io.memoryos.ingestion.extraction;

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
 * @param frames each page's CropBox in user space and its {@code /Rotate}, one per page
 * @param scanned the text layer holds fewer than {@link DoclingSourceContentExtractor#MIN_FALLBACK_CHARACTERS_PER_PDF_PAGE}
 *                non-whitespace characters a page; false when the density was not measured
 */
record PdfLayout(int pages, List<Frame> frames, boolean scanned) {
    PdfLayout {
        frames = List.copyOf(frames);
    }

    /**
     * A page's CropBox as stored: lower-left corner and size in PDF user space (y up), before
     * {@code /Rotate}, which is normalized to 0, 90, 180 or 270. The viewer receives the same box as
     * the page's {@code view}.
     */
    record Frame(double x0, double y0, double width, double height, int rotation) {}

    static PdfLayout of(PDDocument pdf, boolean measureTextLayer) throws IOException {
        int pages = pdf.getNumberOfPages();
        var frames = new ArrayList<Frame>(pages);
        for (int index = 0; index < pages; index++) {
            var page = pdf.getPage(index);
            var box = page.getCropBox();
            frames.add(new Frame(box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight(),
                    Math.floorMod(page.getRotation(), 360)));
        }
        return new PdfLayout(pages, frames, measureTextLayer && scanned(pdf, pages));
    }

    /**
     * Each page in points as a renderer draws it: width and height swapped for a page turned by
     * {@code /Rotate} 90 or 270.
     */
    List<Page> sizes() {
        var sizes = new ArrayList<Page>(frames.size());
        for (int index = 0; index < frames.size(); index++) {
            var frame = frames.get(index);
            boolean turned = frame.rotation() == 90 || frame.rotation() == 270;
            sizes.add(new Page(index + 1, turned ? frame.height() : frame.width(),
                    turned ? frame.width() : frame.height()));
        }
        return List.copyOf(sizes);
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
