package io.memoryos.api.search;

import io.memoryos.retrieval.DocumentOriginalService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;

/** Original PDF bytes are served as a non-executable, uncached attachment, like owner-private Chat files. */
public final class DocumentOriginalResponses {
    private DocumentOriginalResponses() {}

    public static void write(DocumentOriginalService.OriginalPdf pdf, HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("Content-Disposition", ContentDisposition.attachment()
                .filename(pdf.reference().filename(), StandardCharsets.UTF_8).build().toString());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLengthLong(pdf.reference().metadata().sizeBytes());
        pdf.inputStream().transferTo(response.getOutputStream());
    }
}
