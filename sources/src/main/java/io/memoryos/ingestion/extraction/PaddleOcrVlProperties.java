package io.memoryos.ingestion.extraction;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The PaddleOCR-VL layout-parsing service on the GPU node. No endpoint means the provider is absent:
 * scans stay with Docling and Docling keeps its own OCR. There is no API key; the service is
 * reachable only from the application node (MEM-192, "Mạng thay cho API key").
 *
 * @param maxConcurrentRequests requests one worker process has at the service at once; the others
 *        wait in line, and their timeout starts only when they are sent
 */
@ConfigurationProperties("memoryos.extraction.paddleocr-vl")
public record PaddleOcrVlProperties(@Nullable URI endpoint, Duration timeout, int maxPages, String revision,
        @Nullable Integer maxConcurrentRequests) {
    /** PaddleOCR-VL-1.6 at its model revision, served by PaddleX 3.6.1 with the PP-DocLayoutV3 layout model. */
    static final String DEFAULT_REVISION =
            "PaddleOCR-VL-1.6@c5630abae1d940eafe0697512a0325494b02ab42,PaddleX-3.6.1,PP-DocLayoutV3";

    @ConstructorBinding
    public PaddleOcrVlProperties {
        if (endpoint != null && endpoint.toString().isBlank()) endpoint = null;
        // A scan runs 1.5-2.3 s a page on the RTX 4090 (spike, 2026-09-23): 200 pages is some eight
        // minutes alone. Staging shares that GPU with production, so a request can take several
        // times that once it is sent; waiting for a permit is not counted.
        timeout = timeout == null ? Duration.ofMinutes(30) : timeout;
        maxPages = maxPages == 0 ? 200 : maxPages;
        maxConcurrentRequests = maxConcurrentRequests == null ? 2 : maxConcurrentRequests;
        revision = revision == null || revision.isBlank() ? DEFAULT_REVISION : revision.strip();
        if ((endpoint != null && (!Set.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null))
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1)) > 0
                || maxPages < 1 || maxPages > 200 || revision.length() > 200 || revision.contains(";")
                || maxConcurrentRequests < 1 || maxConcurrentRequests > 16) {
            throw new IllegalArgumentException("invalid PaddleOCR-VL extraction configuration");
        }
    }

    public static PaddleOcrVlProperties disabled() {
        return new PaddleOcrVlProperties(null, null, 0, null, null);
    }

    public boolean configured() {
        return endpoint != null;
    }

    String parserConfiguration(long maxInput) {
        return "memoryos-extraction-v2;paddleocr-vl=" + revision
                + ";visualize=false;mergeTables=false;docOrientation=false;docUnwarping=false"
                + ";maxPages=" + maxPages + ";timeoutSeconds=" + timeout.toSeconds() + ";maxInput=" + maxInput
                + ";maxOutput=33554432;tableHeaders=none"
                + ";tableText=sparse-offsets-v1;financialChecks=cash-flow-income-v2";
    }

    @Override public String toString() { return "PaddleOcrVlProperties[configured=" + configured() + "]"; }
}
