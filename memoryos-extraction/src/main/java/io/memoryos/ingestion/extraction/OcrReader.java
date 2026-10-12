package io.memoryos.ingestion.extraction;

import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import org.jspecify.annotations.Nullable;

/**
 * One OCR connection reading scans and images. Its failures stand: a scan is never handed to Docling's OCR or the
 * native reader instead, whose misread amounts would be indexed as if they were right (MEM-192).
 */
interface OcrReader extends AutoCloseable {
    /** Whether an image of this type is this reader's; a scanned PDF always is. */
    boolean readsImage(String mediaType);

    /**
     * @param layout the PDF's pages; null for an image
     * @return the document, or null when the input holds no text at all (a photograph)
     */
    @Nullable DocumentContent read(PaddleOcrVlClient.Input input, @Nullable PdfLayout layout, String filename,
            String mediaType, long maxInput) throws ExtractionException;

    /** A blank result is malformed, as it is for Docling: an index entry that answers nothing is not a success. */
    default DocumentContent extract(PaddleOcrVlClient.Input input, @Nullable PdfLayout layout, String filename,
            String mediaType, long maxInput) throws ExtractionException {
        var content = read(input, layout, filename, mediaType, maxInput);
        if (content == null) throw DocumentAssembly.failure(ExtractionFailure.MALFORMED);
        return content;
    }

    @Override void close();
}
