package io.memoryos.chat.web;

import java.io.IOException;
import java.io.Writer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.jspecify.annotations.NullMarked;

/** Bounded text-only public PDF reading; never renders images, runs scripts or invokes OCR. */
@NullMarked
final class WebPdfReader {
    private static final int MAX_PAGES = 50;
    private static final int MAX_CHARACTERS = 16000;

    private WebPdfReader() {}

    static WebProviderClient.Result read(byte[] bytes, String url, Runnable active) throws IOException {
        active.run();
        try (var document = Loader.loadPDF(bytes)) {
            if (!document.getCurrentAccessPermission().canExtractContent())
                throw new IOException("PDF does not permit text extraction");
            var output = new BoundedText(active);
            var stripper = new PDFTextStripper() {
                @Override public void processPage(PDPage page) throws IOException {
                    active.run();
                    super.processPage(page);
                }
                @Override protected void processTextPosition(TextPosition text) {
                    active.run();
                    super.processTextPosition(text);
                }
            };
            stripper.setEndPage(MAX_PAGES);
            boolean truncated = document.getNumberOfPages() > MAX_PAGES;
            try { stripper.writeText(document, output); }
            catch (TextLimit reached) { truncated = true; }
            active.run();
            if (output.text.toString().isBlank()) throw new IOException("PDF has no readable text; OCR is not available in the Web reader");
            String title = document.getDocumentInformation().getTitle();
            if (title == null || title.isBlank()) title = url;
            return new WebProviderClient.Result(url, title.substring(0, Math.min(title.length(), 1024)),
                    output.text + (truncated ? "\n[PDF excerpt truncated; remaining content was not read.]" : ""));
        }
    }

    private static final class TextLimit extends IOException {}

    private static final class BoundedText extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final Runnable active;
        private BoundedText(Runnable active) { this.active = active; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            active.run();
            int allowed = Math.min(length, MAX_CHARACTERS - text.length());
            text.append(chars, offset, allowed);
            if (allowed < length) throw new TextLimit();
        }
        @Override public void flush() {}
        @Override public void close() {}
    }
}
